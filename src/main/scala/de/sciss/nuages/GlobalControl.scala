/*
 *  GlobalControl.scala
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

import java.awt.Point
import java.awt.event.{KeyEvent, KeyListener, MouseEvent, MouseMotionListener}
import java.awt.geom.Point2D

import de.sciss.lucre.synth.Sys
import prefuse.visual.NodeItem

import scala.annotation.switch

final class GlobalControl[S <: Sys[S]](main: NuagesPanel[S]) extends MouseMotionListener with KeyListener {
  private val lastPt        = new Point
  private val p2d           = new Point2D.Float // throw-away
  private var lastCollector = null : NodeItem

  main.display.addMouseMotionListener(this)
  main.display.addKeyListener        (this)

  def mouseDragged(e: MouseEvent): Unit = lastPt.setLocation(e.getX, e.getY)
  def mouseMoved  (e: MouseEvent): Unit = lastPt.setLocation(e.getX, e.getY)

  def keyTyped(e: KeyEvent): Unit =
    (e.getKeyChar: @switch) match {
      case '1' => zoom(1.0)
      case '2' => zoom(2.0)
      case 'o' => panToNextCollector()
      case _   =>
    }

  private def panToNextCollector(): Unit = {
    val display = main.display
    if (display.isTranformInProgress) return

    // yes, this code is ugly, but so is
    // chasing a successor on a non-cyclic iterator...
    val it = main.visualGraph.nodes()
    var first = null : NodeItem
    var last  = null : NodeItem
    var pred  = null : NodeItem
    var stop  = false
    while (it.hasNext && !stop) {
      val ni = it.next().asInstanceOf[NodeItem]
      val ok = ni.get(NuagesPanel.COL_NUAGES) match {
        case vo: VisualObj[_] => vo.inputs.single.keySet == Set("in")
        case _ => false
      }
      if (ok) {
        if (first == null) first = ni
        pred = last
        last = ni
        if (pred == lastCollector) stop = true
      }
    }
    lastCollector = if (stop) last else first
    if (lastCollector != null) {
      val vi = main.visualization.getVisualItem(NuagesPanel.GROUP_GRAPH, lastCollector)
      if (vi != null) {
        // println(s"Index = $i")
        val b = vi.getBounds
        p2d.setLocation(b.getCenterX, b.getCenterY) // vi.getX + b.getWidth/2, vi.getY + b.getHeight/2)
        display.animatePanToAbs(p2d, 200)
      }
    }
  }

  private def zoom(v: Double): Unit = {
    val display = main.display
    if (display.isTranformInProgress) return

    display.getAbsoluteCoordinate(lastPt, p2d)
    val scale = display.getScale
    // display.zoomAbs(p2d, v / scale)
    display.animateZoomAbs(p2d, v / scale, 200)
  }

  def keyPressed (e: KeyEvent): Unit = ()
  def keyReleased(e: KeyEvent): Unit = ()
}
