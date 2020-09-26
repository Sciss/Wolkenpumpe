/*
 *  NuagesTimelineAttrInput.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2020 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.lucre.{IdentMap, Obj, Source, SpanLikeObj, Txn, synth}
import de.sciss.nuages.NuagesAttribute.{Input, Parent}
import de.sciss.span.{Span, SpanLike}
import de.sciss.synth.proc.Timeline.Timed
import de.sciss.synth.proc.{TimeRef, Timeline, Transport}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.TSet

object NuagesTimelineAttrInput extends NuagesAttribute.Factory {
  def typeId: Int = Timeline.typeId

  type Repr[T <: Txn[T]] = Timeline[T]

  def apply[T <: synth.Txn[T]](attr: NuagesAttribute[T], parent: Parent[T], frameOffset: Long, value: Timeline[T])
                         (implicit tx: T, context: NuagesContext[T]): Input[T] = {
    val map = tx.newIdentMap[Input[T]]
    new NuagesTimelineAttrInput(attr, frameOffset = frameOffset, map = map).init(value, parent)
  }

  def tryConsume[T <: synth.Txn[T]](oldInput: Input[T], newOffset0: Long, newValue: Timeline[T])
                              (implicit tx: T, context: NuagesContext[T]): Option[Input[T]] = {
    val attr          = oldInput.attribute
    val parent        = attr.parent
    val main          = parent.main
    val _frameOffset  = parent.frameOffset
    if (_frameOffset == Long.MaxValue) return None  // what should we do?
    val transportPos  = main.transport.position
    val currentOffset = transportPos - _frameOffset

    newValue.intersect(currentOffset).toList match {
      case (_ /* span */, Vec(entry)) :: Nil =>
        val head = entry.value
        if (oldInput.tryConsume(newOffset = ???!, newValue = head)) {
          val map = tx.newIdentMap[Input[T]]
          val res = new NuagesTimelineAttrInput(attr, frameOffset = _frameOffset, map = map)
            .consume(entry, oldInput, newValue, attr.inputParent)
          Some(res)
        } else None

      case _ => None
    }
  }
}
final class NuagesTimelineAttrInput[T <: synth.Txn[T]] private(val attribute: NuagesAttribute[T],
                                                          protected val frameOffset: Long,
                                                          map: IdentMap[T, Input[T]])
                                                         (implicit context: NuagesContext[T])
  extends NuagesAttrInputBase[T] with NuagesTimelineBase[T] with Parent[T] {

  import Txn.peer

  override def toString = s"$attribute tl[$frameOffset / ${TimeRef.framesToSecs(frameOffset)}]"

  private[this] val viewSet = TSet.empty[Input[T]]

  protected var timelineH: Source[T, Timeline[T]] = _

  protected def transport: Transport[T] = attribute.parent.main.transport

  def tryConsume(newOffset: Long, to: Obj[T])(implicit tx: T): Boolean = false

  def input(implicit tx: T): Obj[T] = timelineH()

  private def init(tl: Timeline[T], parent: Parent[T])(implicit tx: T): this.type = {
    log(s"$attribute timeline init")
    timelineH   = tx.newHandle(tl)
    inputParent = parent
    initPosition()
    initTimeline(tl)
    initTransport()
    this
  }

  def numChildren(implicit tx: T): Int = collect { case x => x.numChildren } .sum

  private def consume(timed: Timed[T], childView: Input[T], tl: Timeline[T], parent: Parent[T])
                     (implicit tx: T): this.type = {
    log(s"$attribute timeline consume")
    timelineH             = tx.newHandle(tl)
    inputParent           = parent
    childView.inputParent = this
    initTimelineObserver(tl)
    viewSet              += childView
    map.put(timed.id, childView)
    initTransport()
    this
  }

  def collect[A](pf: PartialFunction[Input[T], A])(implicit tx: T): Iterator[A] =
    viewSet.iterator.flatMap(_.collect(pf))

  def updateChild(before: Obj[T], now: Obj[T], dt: Long, clearRight: Boolean)(implicit tx: T): Unit = {
    if (dt != 0L) ???!
    removeChild(before)
    addChild(now)
  }

  def addChild(child: Obj[T])(implicit tx: T): Unit = {
    val tl = timelineH()

    def mkSpan(): SpanLikeObj.Var[T] = {
      val start = currentOffset()
      SpanLikeObj.newVar[T](Span.from(start))
    }

    if (isTimeline) {
      val span = mkSpan()
      tl.modifiableOption.fold[Unit] {
        ???!
      } { tlm =>
        tlm.add(span, child)
      }
    } else {
      ???!
    }
  }

  def removeChild(child: Obj[T])(implicit tx: T): Unit = {
    val tl = timelineH()
    if (isTimeline) {
      val stop = currentOffset()
      val entries = tl.intersect(stop).flatMap { case (_ /* span */, xs) =>
        xs.filter(_.value == child)
      }
      entries.foreach { timed =>
        val oldSpan     = timed.span
        val newSpanVal  = oldSpan.value.intersect(Span.until(stop))
        oldSpan match {
          case SpanLikeObj.Var(vr) => vr() = newSpanVal
          case _ =>
            tl.modifiableOption.foreach { tlM =>
              val newSpan = SpanLikeObj.newVar[T](newSpanVal)
              tlM.remove(oldSpan, timed.value)
              tlM.add   (newSpan, timed.value)
            }
        }
      }

    } else {
      ???!
    }
  }

  protected def addNode(span: SpanLike, timed: Timed[T])(implicit tx: T): Unit = {
    log(s"$attribute timeline addNode $timed")
    val childOffset = if (frameOffset == Long.MaxValue) Long.MaxValue else span match {
      case hs: Span.HasStart  => frameOffset + hs.start
      case _                  => Long.MaxValue
    }
    val childView = NuagesAttribute.mkInput(attribute, parent = this, frameOffset = childOffset, value = timed.value)
    viewSet += childView
    map.put(timed.id, childView)
  }

  protected def removeNode(span: SpanLike, timed: Timed[T])(implicit tx: T): Unit = {
    log(s"$attribute timeline removeNode $timed")
    val childView = map.getOrElse(timed.id, throw new IllegalStateException(s"View for $timed not found"))
    val found = viewSet.remove(childView)
    assert(found)
    childView.dispose()
  }

  def dispose()(implicit tx: T): Unit = {
    log(s"$attribute timeline dispose")
    disposeTransport()
    map.dispose()
    viewSet.foreach(_.dispose())
    viewSet.clear()
  }
}