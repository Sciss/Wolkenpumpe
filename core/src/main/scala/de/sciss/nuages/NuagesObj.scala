/*
 *  VisualObj.scala
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

import java.awt.geom.Point2D

import de.sciss.lucre.{Ident, Obj, SpanLikeObj, Txn, TxnLike, synth}
import de.sciss.span.SpanLike
import de.sciss.synth.proc.AuralObj
import prefuse.visual.AggregateItem

object NuagesObj {
  def apply[T <: synth.Txn[T]](main: NuagesPanel[T], locOption: Option[Point2D],
                          id: Ident[T], obj: Obj[T], spanValue: SpanLike, spanOption: Option[SpanLikeObj[T]],
                          hasMeter: Boolean, hasSolo: Boolean)
                         (implicit tx: T, context: NuagesContext[T]): NuagesObj[T] =
    impl.NuagesObjImpl(main, locOption = locOption, id = id, obj = obj,
      spanValue = spanValue, spanOption = spanOption,
      hasMeter = hasMeter, hasSolo = hasSolo)
}

/** The GUI representation of a `proc.Obj`.
  *
  * @see [[Obj]]
  */
trait NuagesObj[T <: Txn[T]]
  extends NuagesNode[T] {

  def main: NuagesPanel[T]

  /** Frame with respect to the object's parent at which the object begins to exist. */
  def frameOffset: Long

  // ---- methods to be called on the EDT ----

  /** GUI property: name of the object to display. */
  var name: String

  def aggregate: AggregateItem

//  /** GUI property: whether the object is heard through the solo function or not. */
//  var soloed: Boolean

  def meterUpdate(newPeak: Double): Unit

  // ---- transactional methods ----

  def obj       (implicit tx: T): Obj[T]
  def spanOption(implicit tx: T): Option[SpanLikeObj[T]]

  def isCollector(implicit tx: TxnLike): Boolean

  def hasOutput(key: String)(implicit tx: TxnLike): Boolean
  def getOutput(key: String)(implicit tx: TxnLike): Option[NuagesOutput[T]]

  def setSolo(onOff: Boolean)(implicit tx: T): Unit

  def auralObjAdded  (aural: AuralObj[T])(implicit tx: T): Unit
  def auralObjRemoved(aural: AuralObj[T])(implicit tx: T): Unit

  def outputs   (implicit tx: T): Map[String, NuagesOutput   [T]] = throw new NotImplementedError()
  def attributes(implicit tx: T): Map[String, NuagesAttribute[T]] = throw new NotImplementedError()

  def removeSelf()(implicit tx: T): Unit = throw new NotImplementedError()
}