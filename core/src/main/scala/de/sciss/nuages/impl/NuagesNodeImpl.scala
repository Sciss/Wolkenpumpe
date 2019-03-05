/*
 *  NuagesNodeImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2019 Hanns Holger Rutz. All rights reserved.
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

trait NuagesNodeImpl[S <: Sys[S]] extends NuagesDataImpl[S] with NuagesNode[S] {
  protected final def atomic[A](fun: S#Tx => A): A = main.universe.cursor.step(fun)
}

trait NuagesNodeRootImpl[S <: Sys[S]] extends NuagesNodeImpl[S] {
  private[this] var _pNode: PNode = _

  // ---- abstract ----

  def parent: NuagesObj[S]

  protected def nodeSize: Float

  // ---- implementation ----

  final def pNode: PNode = {
    if (_pNode == null) throw new IllegalStateException(s"Component $this has no initialized GUI")
    _pNode
  }

  protected final def mkPNode(): VisualItem = {
    if (_pNode != null) throw new IllegalStateException(s"Component $this has already been initialized")
    log(s"mkPNode($name) $this")
    _pNode  = main.graph.addNode()
    val vis = main.visualization
    val vi  = vis.getVisualItem(NuagesPanel.GROUP_GRAPH, _pNode)
    vi.set(NuagesPanel.COL_NUAGES, this)
    val sz  = nodeSize
    if (sz != 1.0f) vi.set(VisualItem.SIZE, sz)
    logAggr(s"add $vi@${vi.hashCode.toHexString} - $this")
    parent.aggr.addItem(vi)
    vi
  }

  protected def disposeGUI(): Unit = {
    log(s"disposeGUI($name)")
    val _vi = main.visualization.getVisualItem(NuagesPanel.GROUP_GRAPH, pNode)
    logAggr(s"rem ${_vi}@${_vi.hashCode.toHexString} - $this")
    parent.aggr.removeItem(_vi)
    main.graph.removeNode(pNode)
  }
}