/*
 *  PanControl.scala
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

import java.awt.event.MouseEvent

/** A control to pan by pressing on the surface and moving the mouse. */
class PanControl extends prefuse.controls.PanControl {
  private[this] var active = false

  override def mousePressed(e: MouseEvent): Unit = {
    if (e.isShiftDown) return
    active = true
    super.mousePressed(e)
  }

  override def mouseDragged(e: MouseEvent): Unit = if (active) super.mouseDragged(e)

  override def mouseReleased(e: MouseEvent): Unit = if (active) {
    super.mouseReleased(e)
    active = false
  }
}