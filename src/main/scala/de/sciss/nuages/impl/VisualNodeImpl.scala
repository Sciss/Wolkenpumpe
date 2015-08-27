/*
 *  VisualNodeImpl.scala
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
package impl

import de.sciss.lucre.synth.Sys
import prefuse.data.{Node => PNode}
import prefuse.visual.VisualItem

trait VisualNodeImpl[S <: Sys[S]] extends VisualDataImpl[S] with VisualNode[S] {
  private[this] var _pNode: PNode = _

  protected def parent: VisualObj[S]

  protected def nodeSize: Float

  final def pNode: PNode = {
    if (_pNode == null) throw new IllegalStateException(s"Component $this has no initialized GUI")
    _pNode
  }

  protected final def mkPNode(): VisualItem = {
    if (_pNode != null) throw new IllegalStateException(s"Component $this has already been initialized")
    _pNode  = main.graph.addNode()
    val vis = main.visualization
    val vi  = vis.getVisualItem(NuagesPanel.GROUP_GRAPH, _pNode)
    vi.set(NuagesPanel.COL_NUAGES, this)
    val sz  = nodeSize
    if (sz != 1.0f) vi.set(VisualItem.SIZE, sz)
    parent.aggr.addItem(vi)
    vi
  }

  protected final def atomic[A](fun: S#Tx => A): A = main.transport.scheduler.cursor.step(fun)
}
