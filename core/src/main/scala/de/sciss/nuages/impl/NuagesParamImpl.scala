/*
 *  NuagesParamImpl.scala
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
import prefuse.data.{Edge => PEdge}
import prefuse.visual.VisualItem

trait NuagesParamImpl[T <: Txn[T]] extends NuagesDataImpl[T] /* NuagesNodeImpl[T] */ with NuagesParam[T] {
//  final def pEdge: PEdge = {
//    if (_pEdge == null) throw new IllegalStateException(s"Component $this has no initialized GUI")
//    _pEdge
//  }

  final def name: String          = key
  final def main: NuagesPanel[T]  = parent.main
}

trait NuagesParamRootImpl[T <: Txn[T]] extends NuagesParamImpl[T] with NuagesNodeRootImpl[T] {
  private[this] var _pEdge: PEdge = _

  protected final def mkPNodeAndEdge(): VisualItem = {
    val vi = mkPNode()
    if (_pEdge != null) throw new IllegalStateException(s"Component $this has already been initialized")
    val g   = main.graph
    _pEdge  = g.addEdge(parent.pNode, pNode)
    log(s"mkPNodeAndEdge($name) $this")
    val pVi = main.visualization.getVisualItem(NuagesPanel.GROUP_GRAPH, parent.pNode)
    vi.setEndX(pVi.getEndX)
    vi.setEndY(pVi.getEndY)
    vi
  }
}