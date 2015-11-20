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

import java.awt.geom.Point2D

import de.sciss.lucre.stm.{Sys, Obj}
import de.sciss.lucre.synth.{Synth, Sys => SSys}
import de.sciss.synth.proc.Timeline.Timed
import prefuse.visual.AggregateItem

import scala.concurrent.stm.TMap

object NuagesObj {
  def apply[S <: SSys[S]](main: NuagesPanel[S], locOption: Option[Point2D],
                         timed: Timed[S],
                         hasMeter: Boolean, hasSolo: Boolean)
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesObj[S] =
    impl.NuagesObjImpl(main, locOption, timed, hasMeter = hasMeter, hasSolo = hasSolo)
}

/** The GUI representation of a `proc.Obj`.
  *
  * @see [[Obj]]
  */
trait NuagesObj[S <: Sys[S]]
  extends NuagesNode[S] {

  def main: NuagesPanel[S]

//  def spanH: stm.Source[S#Tx, SpanLikeObj[S]]
//  def objH : stm.Source[S#Tx, Obj[S]]

  def timed(implicit tx: S#Tx): Timed[S]
  def obj  (implicit tx: S#Tx): Obj[S]    = timed.value

  // ---- methods to be called on the EDT ----

  /** GUI property: name of the object to display. */
  var name: String

  def aggr: AggregateItem

  val inputs : TMap[String, NuagesOutput   [S]]
  val outputs: TMap[String, NuagesOutput   [S]]
  val params : TMap[String, NuagesAttribute[S]]

  /** GUI property: whether the object is heard through the solo function or not. */
  var soloed: Boolean

  def meterUpdate(newPeak: Double): Unit

  // ---- transactional methods ----

  def meterSynth(implicit tx: S#Tx): Option[Synth]
  def meterSynth_=(value: Option[Synth])(implicit tx: S#Tx): Unit
}