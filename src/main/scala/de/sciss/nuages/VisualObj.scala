/*
 *  VisualObj.scala
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

import de.sciss.lucre.expr.SpanLikeObj
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, Obj}
import de.sciss.lucre.synth.{Synth, Sys}
import prefuse.visual.AggregateItem

object VisualObj {
  def apply[S <: Sys[S]](main: NuagesPanel[S], span: SpanLikeObj[S], obj: Obj[S],
                         hasMeter: Boolean, hasSolo: Boolean)
                        (implicit tx: S#Tx): VisualObj[S] =
    impl.VisualObjImpl(main, span, obj, hasMeter = hasMeter, hasSolo = hasSolo)
}

/** The GUI representation of a `proc.Obj`.
  *
  * @see [[Obj]]
  */
trait VisualObj[S <: Sys[S]]
  extends VisualNode[S] with Disposable[S#Tx] {

  def main: NuagesPanel[S]

  def spanH: stm.Source[S#Tx, SpanLikeObj[S]]
  def objH : stm.Source[S#Tx, Obj[S]]

  // ---- methods to be called on the EDT ----

  /** GUI property: name of the object to display. */
  var name: String

  def aggr: AggregateItem

  var inputs : Map[String, VisualScan   [S]]
  var outputs: Map[String, VisualScan   [S]]
  var params : Map[String, VisualControl[S]]

  /** GUI property: whether the object is heard through the solo function or not. */
  var soloed: Boolean

  def meterUpdate(newPeak: Float): Unit

  // ---- transactional methods ----

  def meterSynth(implicit tx: S#Tx): Option[Synth]
  def meterSynth_=(value: Option[Synth])(implicit tx: S#Tx): Unit
}