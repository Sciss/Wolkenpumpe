/*
 *  VisualParam.scala
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

import de.sciss.lucre.synth.Sys
import prefuse.data.Edge

trait VisualParam[S <: Sys[S]] extends VisualNode[S] {
  // ---- methods to be called on the EDT ----

  /** The corresponding Prefuse edge. */
  def pEdge: Edge

  def parent: VisualObj[S]

  /** The scan or attribute key in `parent` to point to this component. */
  def key: String
}
