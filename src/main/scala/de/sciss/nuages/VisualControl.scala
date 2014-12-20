/*
 *  VisualControl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2014 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.lucre.synth.{Synth, Sys}
import de.sciss.synth.proc.{Scan, DoubleElem}

import scala.concurrent.stm.Ref

object VisualControl {
  def scalar[S <: Sys[S]](parent: VisualObj[S], key: String,
                          dObj: DoubleElem.Obj[S])(implicit tx: S#Tx): VisualControl[S] =
    impl.VisualControlImpl.scalar(parent, key, dObj)

  def scan[S <: Sys[S]](parent: VisualObj[S], key: String,
                        sObj: Scan.Obj[S])(implicit tx: S#Tx): VisualControl[S] =
    impl.VisualControlImpl.scan(parent, key, sObj)


  trait Mapping[S <: Sys[S]] {
    /** The metering synth that via `SendTrig` updates the control's current value. */
    def synth: Ref[Option[Synth]]

    var source: Option[VisualScan[S]]
  }
}
trait VisualControl[S <: Sys[S]] extends VisualParam[S] {

  def spec: ParamSpec

  /** The value is normalized in the range 0 to 1 */
  var value: Double

  def mapping: Option[VisualControl.Mapping[S]]

  def removeMapping()(implicit tx: S#Tx): Unit
}