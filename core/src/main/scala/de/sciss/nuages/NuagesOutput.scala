/*
 *  VisualScan.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2021 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.lucre.synth.{AudioBus, Synth}
import de.sciss.lucre.{Txn, synth}
import de.sciss.nuages.NuagesAttribute.Input
import de.sciss.proc.Proc

object NuagesOutput {
  def apply[T <: synth.Txn[T]](parent: NuagesObj[T], output: Proc.Output[T], meter: Boolean)
                        (implicit tx: T, context: NuagesContext[T]): NuagesOutput[T] =
    impl.NuagesOutputImpl(parent, output = output, meter = meter)

  trait Input[T <: Txn[T]] extends NuagesAttribute.Input[T] {
    def output(implicit tx: T): Proc.Output[T]
  }

  trait Meter {
    def bus   : AudioBus
    def synth : Synth
  }
}
trait NuagesOutput[T <: Txn[T]] extends NuagesParam[T] with NuagesNode[T] {
  def mappings(implicit tx: T): Set[Input[T]]

  def meterOption(implicit tx: T): Option[NuagesOutput.Meter]

  def addMapping   (view: Input[T])(implicit tx: T): Unit
  def removeMapping(view: Input[T])(implicit tx: T): Unit

  def output(implicit tx: T): Proc.Output[T]

  def setSolo(onOff: Boolean)(implicit tx: T): Unit
}