/*
 *  VisualScan.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2019 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.lucre.stm.Sys
import de.sciss.lucre.synth.{AudioBus, Synth, Sys => SSys}
import de.sciss.nuages.NuagesAttribute.Input
import de.sciss.synth.proc

object NuagesOutput {
  def apply[S <: SSys[S]](parent: NuagesObj[S], output: proc.Output[S], meter: Boolean)
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesOutput[S] =
    impl.NuagesOutputImpl(parent, output = output, meter = meter)

  trait Input[S <: Sys[S]] extends NuagesAttribute.Input[S] {
    def output(implicit tx: S#Tx): proc.Output[S]
  }

  trait Meter {
    def bus   : AudioBus
    def synth : Synth
  }
}
trait NuagesOutput[S <: Sys[S]] extends NuagesParam[S] with NuagesNode[S] {
  def mappings(implicit tx: S#Tx): Set[Input[S]]

  def meterOption(implicit tx: S#Tx): Option[NuagesOutput.Meter]

  def addMapping   (view: Input[S])(implicit tx: S#Tx): Unit
  def removeMapping(view: Input[S])(implicit tx: S#Tx): Unit

  def output(implicit tx: S#Tx): proc.Output[S]

  def setSolo(onOff: Boolean)(implicit tx: S#Tx): Unit
}