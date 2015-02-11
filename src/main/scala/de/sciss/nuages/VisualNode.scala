/*
 *  VisualNode.scala
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
import prefuse.data.{Node => PNode}

/** The common super type of all Prefuse objects
  * that have a central node.
  *
  * The sub-types are `VisualObj` and `VisualParam`.
  *
  * @see [[VisualObj]]
  * @see [[VisualParam]]
  */
trait VisualNode[S <: Sys[S]] extends VisualData[S] {
  // ---- methods to be called on the EDT ----

  /** The corresponding Prefuse node. */
  def pNode: PNode
}

