/*
 *  NuagesTimelineAttrInput.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2016 Hanns Holger Rutz. All rights reserved.
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
import de.sciss.lucre.stm.{Obj, IdentifierMap, Sys, TxnLike}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.{Parent, Input}
import de.sciss.span.{SpanLike, Span}
import de.sciss.synth.proc.Timeline.Timed
import de.sciss.synth.proc.{Timeline, Transport}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.TSet

object NuagesTimelineAttrInput extends NuagesAttribute.Factory {
  def typeID: Int = Timeline.typeID

  type Repr[S <: Sys[S]] = Timeline[S]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: Parent[S], frameOffset: Long, value: Timeline[S])
                         (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] = {
    val map = tx.newInMemoryIDMap[Input[S]]
    new NuagesTimelineAttrInput(attr, frameOffset = frameOffset, map = map).init(value, parent)
  }

  def tryConsume[S <: SSys[S]](oldInput: Input[S], newValue: Timeline[S])
                              (implicit tx: S#Tx, context: NuagesContext[S]): Option[Input[S]] = {
    val attr    = oldInput.attribute
    val parent  = attr.inputParent
    val main    = attr.parent.main
    val time    = main.transport.position // XXX TODO -- should find a currentFrame somewhere
    newValue.intersect(time).toList match {
      case (span, Vec(entry)) :: Nil =>
        val head = entry.value
        if (oldInput.tryConsume(head)) {
          val map = tx.newInMemoryIDMap[Input[S]]
          val res = new NuagesTimelineAttrInput(attr, frameOffset = ???, map = map)
            .consume(entry, oldInput, newValue, parent)
          Some(res)
        } else None

      case _ => None
    }
  }
}
final class NuagesTimelineAttrInput[S <: SSys[S]] private(val attribute: NuagesAttribute[S],
                                                          protected val frameOffset: Long,
                                                          map: IdentifierMap[S#ID, S#Tx, Input[S]])
                                                         (implicit context: NuagesContext[S])
  extends NuagesAttrInputBase[S] with NuagesTimelineBase[S] with Parent[S] {

  import TxnLike.peer

  private[this] val viewSet = TSet.empty[Input[S]]

  protected var timelineH: stm.Source[S#Tx, Timeline[S]] = _

  //  // N.B.: Currently AuralTimelineAttribute does not pay
  //  // attention to the parent object's time offset. Therefore,
  //  // to match with the current audio implementation, we also
  //  // do not take that into consideration, but might so in the future...
  //  protected def currentFrame()(implicit tx: S#Tx): Long = {
  //    // attribute.parent.spanValue
  //    transport.position
  ////    val parentView  = attribute.parent
  ////    val spanOption  = parentView.spanOption
  ////    spanOption.fold(0L) { spanObj =>
  ////      spanObj.value match {
  ////        case span: Span.HasStart => transport.position - span.start
  ////        case _ => BiGroup.MaxCoordinate // no offset can be given - we may still have Span.All children
  ////      }
  ////    }
  //  }

  protected def transport: Transport[S] = attribute.parent.main.transport

  def tryConsume(to: Obj[S])(implicit tx: S#Tx): Boolean = false

  def input(implicit tx: S#Tx): Obj[S] = timelineH()

  private def init(tl: Timeline[S], parent: Parent[S])(implicit tx: S#Tx): this.type = {
    log(s"$attribute timeline init")
    timelineH   = tx.newHandle(tl)
    inputParent = parent
    initTimeline(tl)
    initTransport()
    this
  }

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

  def updateChild(before: Obj[S], now: Obj[S])(implicit tx: S#Tx): Unit = ???!

  def addChild(child: Obj[S])(implicit tx: S#Tx): Unit = {
    val tl = timelineH()

    def mkSpan(): SpanLikeObj.Var[S] = {
      val frame = currentOffset()
      SpanLikeObj.newVar[S](Span.from(frame))
    }

    if (isTimeline) {
      // XXX TODO --- DRY with NuagesAttributeImpl#addChild
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

  // XXX DRY --- with PanelImplTxnFuns#removeCollectionAttribute
  def removeChild(child: Obj[S])(implicit tx: S#Tx): Unit = {
    val tl = timelineH()
    if (isTimeline) {
      val frame   = currentOffset()
      val entries = tl.intersect(frame).flatMap { case (span, xs) =>
        xs.filter(_.value == child)
      }
      entries.foreach { timed =>
        val oldSpan     = timed.span
        val newSpanVal  = oldSpan.value.intersect(Span.until(frame))
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
      case hs: Span.HasStart => frameOffset + hs.start
      case _ => Long.MaxValue
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

  def value: Vec[Double] = ???!

  def numChannels: Int = ???!

  def dispose()(implicit tx: S#Tx): Unit = {
    log(s"$attribute timeline dispose")
    disposeTransport()
    map.dispose()
    viewSet.foreach(_.dispose())
    viewSet.clear()
  }
}