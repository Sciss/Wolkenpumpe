/*
 *  NuagesNodeImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2020 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.lucre.synth.Txn
import prefuse.data.{Node => PNode}
import prefuse.visual.VisualItem

trait NuagesNodeImpl[T <: Txn[T]] extends NuagesDataImpl[T] with NuagesNode[T] {
  protected final def atomic[A](fun: T => A): A = main.universe.cursor.step(fun)
}

trait NuagesNodeRootImpl[T <: Txn[T]] extends NuagesNodeImpl[T] {
  private[this] var _pNode: PNode = _

  // ---- abstract ----

  def parent: NuagesObj[T]

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
    parent.aggregate.addItem(vi)
    vi
  }

  protected def disposeGUI(): Unit = {
    log(s"disposeGUI($name)")
    val _vi = main.visualization.getVisualItem(NuagesPanel.GROUP_GRAPH, pNode)
    logAggr(s"rem ${_vi}@${_vi.hashCode.toHexString} - $this")
    parent.aggregate.removeItem(_vi)
    main.graph.removeNode(pNode)
  }
}