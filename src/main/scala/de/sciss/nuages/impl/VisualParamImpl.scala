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
import prefuse.data.{Edge => PEdge}

trait VisualParamImpl[S <: Sys[S]] extends VisualNodeImpl[S] with VisualParam[S] {
  private[this] var _pEdge: PEdge = _

  final def pEdge: PEdge = {
    if (_pEdge == null) throw new IllegalStateException(s"Component $this has no initialized GUI")
    _pEdge
  }

  protected final def mkPNodeAndEdge(): Unit = {
    mkPNode()
    if (_pEdge != null) throw new IllegalStateException(s"Component $this has already been initialized")
    val g   = main.graph
    _pEdge  = g.addEdge(parent.pNode, pNode)
  }

  final def name: String = key
  final def main = parent.main
}
