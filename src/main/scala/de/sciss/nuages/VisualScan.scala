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

import de.sciss.lucre.synth.Sys
import prefuse.data.Edge

object VisualScan {
  // SCAN
//  def apply[S <: Sys[S]](parent: VisualObj[S], scan: Scan[S], key: String, isInput: Boolean)(implicit tx: S#Tx): VisualScan[S] =
//    impl.VisualScanImpl(parent, scan = scan, key = key, isInput = isInput)
}
trait VisualScan[S <: Sys[S]] extends VisualParam[S] {
  var sources : Set[Edge]
  var sinks   : Set[Edge]
  var mappings: Set[VisualControl[S]]

  // SCAN
  // def scan(implicit tx: S#Tx): Scan[S]
}