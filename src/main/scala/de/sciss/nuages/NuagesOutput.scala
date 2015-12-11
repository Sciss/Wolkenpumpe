/*
 *  VisualScan.scala
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

import de.sciss.lucre.stm.Sys
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.synth.proc.Output
import prefuse.data.Edge

object NuagesOutput {
  def apply[S <: SSys[S]](parent: NuagesObj[S], output: Output[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesOutput[S] =
    impl.NuagesOutputImpl(parent, output = output)
}
trait NuagesOutput[S <: Sys[S]] extends NuagesParam[S] with NuagesNode[S] {
  var sources : Set[Edge]
  var sinks   : Set[Edge]
  var mappings: Set[NuagesAttribute[S]]

  // SCAN
  // def scan(implicit tx: S#Tx): Scan[S]
}