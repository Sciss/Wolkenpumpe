package de.sciss.nuages.impl

import java.awt.event.MouseEvent

import prefuse.controls.PanControl

class MyPanControl extends PanControl {
  private var active = false
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
