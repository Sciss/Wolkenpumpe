/*
 *  VisualNode.scala
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

import de.sciss.lucre.Txn
import prefuse.data.{Node => PNode}

/** The common super type of all Prefuse objects
  * that have a central node.
  *
  * The sub-types are `VisualObj` and `VisualParam`.
  *
  * @see [[NuagesObj]]
  * @see [[NuagesParam]]
  */
trait NuagesNode[T <: Txn[T]] extends NuagesData[T] {
  // ---- methods to be called on the EDT ----

  /** The corresponding Prefuse node. */
  def pNode: PNode
}

