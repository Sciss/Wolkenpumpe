/*
 *  NuagesTimelineAttrInput.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2018 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.lucre.expr.SpanLikeObj
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{IdentifierMap, Obj, Sys, TxnLike}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.{Input, Parent}
import de.sciss.span.{Span, SpanLike}
import de.sciss.synth.proc.Timeline.Timed
import de.sciss.synth.proc.{TimeRef, Timeline, Transport}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.TSet

object NuagesTimelineAttrInput extends NuagesAttribute.Factory {
  def typeId: Int = Timeline.typeId

  type Repr[S <: Sys[S]] = Timeline[S]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: Parent[S], frameOffset: Long, value: Timeline[S])
                         (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] = {
    val map = tx.newInMemoryIdMap[Input[S]]
    new NuagesTimelineAttrInput(attr, frameOffset = frameOffset, map = map).init(value, parent)
  }

  def tryConsume[S <: SSys[S]](oldInput: Input[S], newOffset0: Long, newValue: Timeline[S])
                              (implicit tx: S#Tx, context: NuagesContext[S]): Option[Input[S]] = {
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
          val map = tx.newInMemoryIdMap[Input[S]]
          val res = new NuagesTimelineAttrInput(attr, frameOffset = _frameOffset, map = map)
            .consume(entry, oldInput, newValue, attr.inputParent)
          Some(res)
        } else None

      case _ => None
    }
  }
}
final class NuagesTimelineAttrInput[S <: SSys[S]] private(val attribute: NuagesAttribute[S],
                                                          protected val frameOffset: Long,
                                                          map: IdentifierMap[S#Id, S#Tx, Input[S]])
                                                         (implicit context: NuagesContext[S])
  extends NuagesAttrInputBase[S] with NuagesTimelineBase[S] with Parent[S] {

  import TxnLike.peer

  override def toString = s"$attribute tl[$frameOffset / ${TimeRef.framesToSecs(frameOffset)}]"

  private[this] val viewSet = TSet.empty[Input[S]]

  protected var timelineH: stm.Source[S#Tx, Timeline[S]] = _

  protected def transport: Transport[S] = attribute.parent.main.transport

  def tryConsume(newOffset: Long, to: Obj[S])(implicit tx: S#Tx): Boolean = false

  def input(implicit tx: S#Tx): Obj[S] = timelineH()

  private def init(tl: Timeline[S], parent: Parent[S])(implicit tx: S#Tx): this.type = {
    log(s"$attribute timeline init")
    timelineH   = tx.newHandle(tl)
    inputParent = parent
    initPosition()
    initTimeline(tl)
    initTransport()
    this
  }

  def numChildren(implicit tx: S#Tx): Int = collect { case x => x.numChildren } .sum

  private def consume(timed: Timed[S], childView: Input[S], tl: Timeline[S], parent: Parent[S])
                     (implicit tx: S#Tx): this.type = {
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

  def collect[A](pf: PartialFunction[Input[S], A])(implicit tx: S#Tx): Iterator[A] =
    viewSet.iterator.flatMap(_.collect(pf))

  def updateChild(before: Obj[S], now: Obj[S], dt: Long, clearRight: Boolean)(implicit tx: S#Tx): Unit = {
    if (dt != 0L) ???!
    removeChild(before)
    addChild(now)
  }

  def addChild(child: Obj[S])(implicit tx: S#Tx): Unit = {
    val tl = timelineH()

    def mkSpan(): SpanLikeObj.Var[S] = {
      val start = currentOffset()
      SpanLikeObj.newVar[S](Span.from(start))
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

  def removeChild(child: Obj[S])(implicit tx: S#Tx): Unit = {
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
              val newSpan = SpanLikeObj.newVar[S](newSpanVal)
              tlM.remove(oldSpan, timed.value)
              tlM.add   (newSpan, timed.value)
            }
        }
      }

    } else {
      ???!
    }
  }

  protected def addNode(span: SpanLike, timed: Timed[S])(implicit tx: S#Tx): Unit = {
    log(s"$attribute timeline addNode $timed")
    val childOffset = if (frameOffset == Long.MaxValue) Long.MaxValue else span match {
      case hs: Span.HasStart  => frameOffset + hs.start
      case _                  => Long.MaxValue
    }
    val childView = NuagesAttribute.mkInput(attribute, parent = this, frameOffset = childOffset, value = timed.value)
    viewSet += childView
    map.put(timed.id, childView)
  }

  protected def removeNode(span: SpanLike, timed: Timed[S])(implicit tx: S#Tx): Unit = {
    log(s"$attribute timeline removeNode $timed")
    val childView = map.getOrElse(timed.id, throw new IllegalStateException(s"View for $timed not found"))
    val found = viewSet.remove(childView)
    assert(found)
    childView.dispose()
  }

  def dispose()(implicit tx: S#Tx): Unit = {
    log(s"$attribute timeline dispose")
    disposeTransport()
    map.dispose()
    viewSet.foreach(_.dispose())
    viewSet.clear()
  }
}