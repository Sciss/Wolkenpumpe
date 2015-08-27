/*
 *  VisualParamImpl.scala
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

package de.sciss.nuages.impl

import de.sciss.lucre.synth.Sys
import de.sciss.nuages.{NuagesPanel, VisualParam}
import prefuse.data.{Edge => PEdge}
import prefuse.visual.VisualItem

trait VisualParamImpl[S <: Sys[S]] extends VisualNodeImpl[S] with VisualParam[S] {
  private[this] var _pEdge: PEdge = _

  final def pEdge: PEdge = {
    if (_pEdge == null) throw new IllegalStateException(s"Component $this has no initialized GUI")
    _pEdge
  }

  protected final def mkPNodeAndEdge(): VisualItem = {
    val vi = mkPNode()
    if (_pEdge != null) throw new IllegalStateException(s"Component $this has already been initialized")
    val g   = main.graph
    _pEdge  = g.addEdge(parent.pNode, pNode)
    val pVi = main.visualization.getVisualItem(NuagesPanel.GROUP_GRAPH, parent.pNode)
    vi.setEndX(pVi.getX)
    vi.setEndY(pVi.getY)
    vi
  }

  final def name: String = key
  final def main = parent.main
}
