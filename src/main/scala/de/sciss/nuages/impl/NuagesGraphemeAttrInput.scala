/*
 *  NuagesGraphemeAttrInput.scala
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
import de.sciss.lucre.stm.{Obj, IdentifierMap, Sys, TxnLike}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.Input
import de.sciss.synth.proc.{Grapheme, Transport}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.TSet

object NuagesGraphemeAttrInput extends NuagesAttribute.Factory {
  def typeID: Int = Grapheme.typeID

  type Repr[S <: Sys[S]] = Grapheme[S]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: NuagesAttribute.Parent[S], value: Grapheme[S])
                         (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] = {
    val map = tx.newInMemoryIDMap[Input[S]]
    new NuagesGraphemeAttrInput(attr, map).init(value)
  }
}
final class NuagesGraphemeAttrInput[S <: SSys[S]] private(val attribute: NuagesAttribute[S],
                                                          map: IdentifierMap[S#ID, S#Tx, Input[S]])
                                                         (implicit context: NuagesContext[S])
  extends NuagesAttribute.Input[S] with NuagesScheduledBase[S] with NuagesAttribute.Parent[S] {

  import TxnLike.peer

  private[this] val viewSet = TSet.empty[Input[S]]

  protected var graphemeH: stm.Source[S#Tx, Grapheme[S]] = _

  // N.B.: Currently AuralGraphemeAttribute does not pay
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

  private def init(gr: Grapheme[S])(implicit tx: S#Tx): this.type = {
    log(s"$attribute grapheme init")
    graphemeH = tx.newHandle(gr)
    ???!
    initTransport()
    this
  }

  def updateChild(before: Obj[S], now: Obj[S])(implicit tx: S#Tx): Unit = ???!

//  protected def addNode(timed: Timed[S])(implicit tx: S#Tx): Unit = {
//    log(s"$attribute grapheme addNode $timed")
//    val childView = NuagesAttribute.mkInput(attribute, parent = this, value = timed.value)
//    viewSet += childView
//    map.put(timed.id, childView)
//  }

//  protected def removeNode(timed: Timed[S])(implicit tx: S#Tx): Unit = {
//    log(s"$attribute grapheme removeNode $timed")
//    val childView = map.getOrElse(timed.id, throw new IllegalStateException(s"View for $timed not found"))
//    val found = viewSet.remove(childView)
//    assert(found)
//    childView.dispose()
//  }

  def value: Vec[Double] = ???!

  def numChannels: Int = ???!

  def dispose()(implicit tx: S#Tx): Unit = {
    log(s"$attribute grapheme dispose")
    disposeTransport()
    map.dispose()
    viewSet.foreach(_.dispose())
    viewSet.clear()
  }

  protected def seek(before: Long, now: Long)(implicit tx: S#Tx): Unit = ???!

  protected def eventAfter(frame: Long)(implicit tx: S#Tx): Long =
    graphemeH().eventAfter(frame).getOrElse(Long.MaxValue)

  protected def processEvent(frame: Long)(implicit tx: S#Tx): Unit = ???!
}