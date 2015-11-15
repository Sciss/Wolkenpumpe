/*
 *  VisualControl.scala
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

import de.sciss.lucre.expr.{DoubleVector, DoubleObj}
import de.sciss.lucre.synth.{Synth, Sys}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref

object VisualControl {
  def scalar[S <: Sys[S]](parent: VisualObj[S], key: String,
                          dObj: DoubleObj[S])(implicit tx: S#Tx): VisualControl[S] =
    impl.VisualControlImpl.scalar(parent, key, dObj)

  def vector[S <: Sys[S]](parent: VisualObj[S], key: String,
                          dObj: DoubleVector[S])(implicit tx: S#Tx): VisualControl[S] =
    impl.VisualControlImpl.vector(parent, key, dObj)

  // SCAN
//  def scan[S <: Sys[S]](parent: VisualObj[S], key: String,
//                        sObj: Scan[S])(implicit tx: S#Tx): VisualControl[S] =
//    impl.VisualControlImpl.scan(parent, key, sObj)

  trait Mapping[S <: Sys[S]] {
    /** The metering synth that via `SendTrig` updates the control's current value. */
    def synth: Ref[Option[Synth]]

    var source: Option[VisualScan[S]]

    // SCAN
    // def scan(implicit tx: S#Tx): Scan[S]
  }
}
trait VisualControl[S <: Sys[S]] extends VisualParam[S] {

  def spec: ParamSpec

  /** The value is normalized in the range 0 to 1 */
  var value: Vec[Double]

  def numChannels: Int

//  /** The value is normalized in the range 0 to 1 */
//  def value1_=(v: Double): Unit

  def mapping: Option[VisualControl.Mapping[S]]

  def removeMapping()(implicit tx: S#Tx): Unit

  /** Adjusts the control with the given normalized value. */
  def setControl(v: Vec[Double], instant: Boolean): Unit
}