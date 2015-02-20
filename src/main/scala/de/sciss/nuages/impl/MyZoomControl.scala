/*
 *  MyZoomControl.scala
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

import java.awt.Point
import java.awt.event.{MouseEvent, KeyEvent}
import java.awt.geom.Point2D

import prefuse.Display
import prefuse.controls.ZoomControl
import prefuse.visual.VisualItem

/** Additionally supports key pressed '1' (zoom 100%) and '2' (zoom 200%). */
final class MyZoomControl extends ZoomControl {
  private val lastPt        = new Point
  private val lastPtDisplay = new Point2D.Float

  override def mouseMoved                 (e: MouseEvent): Unit = lastPt.setLocation(e.getX, e.getY)
  override def itemMoved(item: VisualItem, e: MouseEvent): Unit = lastPt.setLocation(e.getX, e.getY)

  override def keyTyped                      (e: KeyEvent): Unit = perform(e)
  override def itemKeyTyped(item: VisualItem, e: KeyEvent): Unit = perform(e)

  private def perform(e: KeyEvent): Unit = {
    val ch = e.getKeyChar
    if (ch < '1' || ch > '2') return

    val display = e.getComponent.asInstanceOf[Display]
    if (!display.isTranformInProgress) {
      display.getAbsoluteCoordinate(lastPt, lastPtDisplay)
      val scale = display.getScale
      // display.panAbs(lastPtDisplay.getX, lastPtDisplay.getY)
      zoom(display, lastPtDisplay, (ch - '0') / scale, true)
    }
  }
}
