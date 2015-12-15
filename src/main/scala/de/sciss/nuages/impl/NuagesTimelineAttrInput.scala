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

import de.sciss.lucre.stm.{TxnLike, Disposable, IdentifierMap, Sys}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.Input
import de.sciss.synth.proc.Timeline

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
  extends /* NuagesAttributeImpl[S] */ NuagesAttribute.Input[S] {

  import TxnLike.peer

  private[this] var _observer: Disposable[S#Tx] = _

  private[this] val viewSet = TSet.empty[Input[S]]

  private def init(timeline: Timeline[S])(implicit tx: S#Tx): this.type = {
    ???! // map() = timeline.iterator.map(mkChild).toVector
    _observer = timeline.changed.react { implicit tx => upd => upd.changes.foreach {
      case Timeline.Added  (span, entry) =>
        val view = mkChild(entry)
        ???! // map.transform(_.patch(idx, view :: Nil, 0))
      case Timeline.Removed(span, entry) =>
        ???! // val view = map.getAndTransform(_.patch(idx, Nil, 1)).apply(idx)
        // view.dispose()
      case Timeline.Moved(change, entry) =>
        ???!
    }}
    this
  }

  private[this] def mkChild(elem: Timeline.Timed[S])(implicit tx: S#Tx): NuagesAttribute.Input[S] =
    NuagesAttribute.mkInput(attribute, elem)

  def value: Vec[Double] = ???!

  def numChannels: Int = ???!

  def dispose()(implicit tx: S#Tx): Unit = {
    _observer.dispose()
    map.dispose()
    viewSet.foreach(_.dispose())
    viewSet.clear()
  }
}