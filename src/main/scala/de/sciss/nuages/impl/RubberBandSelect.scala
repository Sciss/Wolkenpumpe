/*
 *  RubberBandSelect.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2016 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages.impl

import java.awt.event.MouseEvent
import java.awt.geom.{Point2D, Rectangle2D}
import javax.swing.SwingUtilities

import prefuse.Display
import prefuse.controls.ControlAdapter
import prefuse.data.Tuple
import prefuse.visual.VisualItem

final class RubberBandSelect(rubberBand: VisualItem) extends ControlAdapter {
  private var dndX = 0
  private var dndY = 0
  private val screenPoint : Point2D  = new Point2D.Float
  private val absPoint    : Point2D  = new Point2D.Float
  private val rect        : Rectangle2D = new Rectangle2D.Float

  private var active = false

  override def mousePressed(e: MouseEvent): Unit = {
    if (!SwingUtilities.isLeftMouseButton(e) || !e.isShiftDown) return

    val d = e.getComponent.asInstanceOf[Display]
    val vis = d.getVisualization
    val focus = vis.getFocusGroup("sel")
    if (!e.isShiftDown) focus.clear()

    val bandRect = rubberBand.get(VisualItem.POLYGON).asInstanceOf[Array[Float]]
    bandRect(0) = 0
    bandRect(1) = 0
    bandRect(2) = 0
    bandRect(3) = 0
    bandRect(4) = 0
    bandRect(5) = 0
    bandRect(6) = 0
    bandRect(7) = 0
    // d.setHighQuality(false)
    screenPoint.setLocation(e.getX, e.getY)
    d.getAbsoluteCoordinate(screenPoint, absPoint)
    dndX = absPoint.getX.toInt
    dndY = absPoint.getY.toInt
    rubberBand.setVisible(true)

    active = true
  }

  override def mouseDragged(e: MouseEvent): Unit = {
    if (!active) return

    val d = e.getComponent.asInstanceOf[Display]
    screenPoint.setLocation(e.getX, e.getY)
    d.getAbsoluteCoordinate(screenPoint, absPoint)
    var x1 = dndX
    var y1 = dndY
    var x2 = absPoint.getX.toInt
    var y2 = absPoint.getY.toInt
    val bandRect = rubberBand.get(VisualItem.POLYGON).asInstanceOf[Array[Float]]
    bandRect(0) = x1
    bandRect(1) = y1
    bandRect(2) = x2
    bandRect(3) = y1
    bandRect(4) = x2
    bandRect(5) = y2
    bandRect(6) = x1
    bandRect(7) = y2
    if (x2 < x1) {
      val temp = x2
      x2 = x1
      x1 = temp
    }
    if (y2 < y1) {
      val temp = y2
      y2 = y1
      y1 = temp
    }
    rect.setRect(x1, y1, x2 - x1, y2 - y1)
    val vis = d.getVisualization
    val focus = vis.getFocusGroup("sel")
    if (!e.isShiftDown) focus.clear()

    // allocate the maximum space we could need
    val selectedItems = new Array[Tuple](vis.getGroup(PanelImpl.GROUP_NODES).getTupleCount)
    val it = vis.getGroup(PanelImpl.GROUP_NODES).tuples()
    // in this example I'm only allowing Nodes to be selected
    var i = 0
    while (it.hasNext) {
      val item = it.next().asInstanceOf[VisualItem]
      if (item.isVisible && item.getBounds.intersects(rect)) {
        selectedItems(i) = item
        i += 1
      }
    }
    // Trim the array down to the actual size
    val properlySizedSelectedItems = new Array[Tuple](i)
    System.arraycopy(selectedItems, 0, properlySizedSelectedItems, 0, i)
    properlySizedSelectedItems.foreach { tuple =>
      focus.addTuple(tuple)
    }
    rubberBand.setValidated(false)
    d.repaint()
  }

  override def mouseReleased(e: MouseEvent): Unit = {
    if (!active) return
    active = false

    rubberBand.setVisible(false)
    val d = e.getComponent.asInstanceOf[Display]
    // d.setHighQuality(true)
    d.getVisualization.repaint()
  }
}