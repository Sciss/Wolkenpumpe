/*
 *  VisualParamImpl.scala
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

package de.sciss.nuages.impl

import de.sciss.lucre.synth.Sys
import de.sciss.nuages.VisualParam
import prefuse.data.Edge

trait VisualParamImpl[S <: Sys[S]] extends VisualNodeImpl[S] with VisualParam[S] {
  var pEdge: Edge  = _

  final def name: String = key
  final def main = parent.main
}
