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

import scala.annotation.switch

final class GlobalControl[S <: Sys[S]](main: NuagesPanel[S]) extends MouseMotionListener with KeyListener {
  private val lastPt        = new Point
  private val lastPtDisplay = new Point2D.Float

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
    // XXX TODO
  }

  private def zoom(v: Double): Unit = {
    val display = main.display
    if (!display.isTranformInProgress) {
      display.getAbsoluteCoordinate(lastPt, lastPtDisplay)
      val scale = display.getScale
      display.zoomAbs(lastPtDisplay, v / scale)
    }
  }

  def keyPressed (e: KeyEvent): Unit = ()
  def keyReleased(e: KeyEvent): Unit = ()
}
