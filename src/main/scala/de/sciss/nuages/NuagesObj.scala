/*
 *  VisualObj.scala
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

import java.awt.geom.Point2D

import de.sciss.lucre.expr.SpanLikeObj
import de.sciss.lucre.stm.{Obj, Sys, TxnLike}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.span.SpanLike
import prefuse.visual.AggregateItem

object NuagesObj {
  def apply[S <: SSys[S]](main: NuagesPanel[S], locOption: Option[Point2D],
                          id: S#ID, obj: Obj[S], spanValue: SpanLike, spanOption: Option[SpanLikeObj[S]],
                          hasMeter: Boolean, hasSolo: Boolean)
                         (implicit tx: S#Tx, context: NuagesContext[S]): NuagesObj[S] =
    impl.NuagesObjImpl(main, locOption = locOption, id = id, obj = obj,
      spanValue = spanValue, spanOption = spanOption,
      hasMeter = hasMeter, hasSolo = hasSolo)
}

/** The GUI representation of a `proc.Obj`.
  *
  * @see [[Obj]]
  */
trait NuagesObj[S <: Sys[S]]
  extends NuagesNode[S] {

  def main: NuagesPanel[S]

  /** Frame with respect to the object's parent at which the object begins to exist. */
  def frameOffset: Long

  // ---- methods to be called on the EDT ----

  /** GUI property: name of the object to display. */
  var name: String

  def aggr: AggregateItem

  /** GUI property: whether the object is heard through the solo function or not. */
  var soloed: Boolean

  def meterUpdate(newPeak: Double): Unit

  // ---- transactional methods ----

  def obj       (implicit tx: S#Tx): Obj[S]
  def spanOption(implicit tx: S#Tx): Option[SpanLikeObj[S]]

  def isCollector(implicit tx: TxnLike): Boolean

  def hasOutput(key: String)(implicit tx: TxnLike): Boolean

  //  def meterSynth(implicit tx: S#Tx): Option[Synth]
  //  def meterSynth_=(value: Option[Synth])(implicit tx: S#Tx): Unit

  //  def addCollectionAttribute(key: String, child: Obj[S])(implicit tx: S#Tx): Unit
  //
  //    /** Removes a child from the attribute map of this view's object.
  //    * If the value currently stored with the attribute map is a collection,
  //    * tries to smartly remove the child from that collection.
  //    *
  //    * @return `true` if the child was found and removed.
  //    */
  //  def removeCollectionAttribute(key: String, child: Obj[S])(implicit tx: S#Tx): Boolean
}