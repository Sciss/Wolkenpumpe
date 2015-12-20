/*
 *  NuagesTimelineAttrInput.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2015 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{IdentifierMap, Sys, TxnLike}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.Input
import de.sciss.synth.proc.Timeline.Timed
import de.sciss.synth.proc.{Timeline, Transport}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.TSet

object NuagesTimelineAttrInput extends NuagesAttribute.Factory {
  def typeID: Int = Timeline.typeID

  type Repr[S <: Sys[S]] = Timeline[S]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], value: Timeline[S])
                         (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] = {
    val map = tx.newInMemoryIDMap[Input[S]]
    new NuagesTimelineAttrInput(attr, map).init(value)
  }
}
final class NuagesTimelineAttrInput[S <: SSys[S]] private(val attribute: NuagesAttribute[S],
                                                          map: IdentifierMap[S#ID, S#Tx, Input[S]])
                                                         (implicit context: NuagesContext[S])
  extends NuagesAttribute.Input[S] with NuagesTimelineTransport[S] {

  import TxnLike.peer

  private[this] val viewSet = TSet.empty[Input[S]]

  protected var timelineH: stm.Source[S#Tx, Timeline[S]] = _

  // N.B.: Currently AuralTimelineAttribute does not pay
  // attention to the parent object's time offset. Therefore,
  // to match with the current audio implementation, we also
  // do not take that into consideration, but might so in the future...
  protected def currentFrame()(implicit tx: S#Tx): Long = {
    transport.position
//    val parentView  = attribute.parent
//    val spanOption  = parentView.spanOption
//    spanOption.fold(0L) { spanObj =>
//      spanObj.value match {
//        case span: Span.HasStart => transport.position - span.start
//        case _ => BiGroup.MaxCoordinate // no offset can be given - we may still have Span.All children
//      }
//    }
  }

  protected def transport: Transport[S] = attribute.parent.main.transport

  private def init(tl: Timeline[S])(implicit tx: S#Tx): this.type = {
    timelineH = tx.newHandle(tl)
    val t     = transport
    initTransport(t, tl)
    this
  }

  protected def addNode(timed: Timed[S])(implicit tx: S#Tx): Unit = {
    log(s"$attribute timeline addNode $timed")
    val childView = NuagesAttribute.mkInput(attribute, timed.value)
    viewSet += childView
    map.put(timed.id, childView)
  }

  protected def removeNode(timed: Timed[S])(implicit tx: S#Tx): Unit = {
    log(s"$attribute timeline removeNode $timed")
    val childView = map.getOrElse(timed.id, throw new IllegalStateException(s"View for $timed not found"))
    val found = viewSet.remove(childView)
    assert(found)
    childView.dispose()
  }

  def value: Vec[Double] = ???!

  def numChannels: Int = ???!

  def dispose()(implicit tx: S#Tx): Unit = {
    disposeTransport()
    map.dispose()
    viewSet.foreach(_.dispose())
    viewSet.clear()
  }
}