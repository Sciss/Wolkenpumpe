/*
 *  VisualScanImpl.scala
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

import java.awt.Graphics2D
import java.awt.event.MouseEvent
import java.awt.geom.Point2D

import de.sciss.lucre.swing.requireEDT
import de.sciss.lucre.synth.Sys
import de.sciss.synth.proc.{Proc, Scan}
import prefuse.data.Edge
import prefuse.visual.VisualItem

object VisualScanImpl {
  def apply[S <: Sys[S]](parent: VisualObj[S], key: String, isInput: Boolean)(implicit tx: S#Tx): VisualScanImpl[S] = {
    val res = new VisualScanImpl(parent, key = key, isInput = isInput)
    parent.main.deferVisTx(res.initGUI())
    res
  }
}
final class VisualScanImpl[S <: Sys[S]] private(val parent: VisualObj[S], val key: String, val isInput: Boolean)
  extends VisualParamImpl[S] with VisualScan[S] {

  import VisualDataImpl._

  protected def nodeSize = 0.333333f

  var sources   = Set.empty[Edge]
  var sinks     = Set.empty[Edge]
  var mappings  = Set.empty[VisualControl[S]]

  def scan(implicit tx: S#Tx): Option[Scan[S]] = parent.objH() match {
    case p: Proc[S] =>
      val proc = p
      val scans = if (isInput) proc.inputs else proc.outputs
      scans.get(key)

    case _ => None
  }

  def initGUI(): Unit = {
    requireEDT()
    mkPNodeAndEdge()
  }

  override def itemPressed(vi: VisualItem, e: MouseEvent, pt: Point2D): Boolean = (key != "in") && {
    // println("itemPressed")
    if (e.getClickCount == 2) {
      parent.main.showAppendFilterDialog(this, e.getPoint)
    }
    true
  }

  protected def boundsResized() = ()

  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit =
    drawName(g, vi, diam * vi.getSize.toFloat * 0.5f)
}
