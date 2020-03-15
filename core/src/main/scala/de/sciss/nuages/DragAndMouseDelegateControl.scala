/*
 *  DragAndMouseDelegateControl.scala
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

import java.awt.Cursor
import java.awt.event.MouseEvent
import java.awt.geom.Point2D
import javax.swing.SwingUtilities

import de.sciss.lucre.synth.Sys
import prefuse.controls.ControlAdapter
import prefuse.visual.{AggregateItem, EdgeItem, NodeItem, VisualItem}
import prefuse.{Display, Visualization}

/** This control allows the moving around of vertices,
  * but also invokes the mouse control on `NuagesData` instances
  * (`itemEntered`, `itemExited`, `itemPressed`, `itemReleased` and `itemDragged`).
  */
object DragAndMouseDelegateControl {

  import NuagesPanel._

  private val csrHand     = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
  private val csrDefault  = Cursor.getDefaultCursor

  def setSmartFixed(vis: Visualization, vi: VisualItem, state: Boolean): Unit = {
    val state1 = state || (getVisualData(vis, vi) match {
      case Some(data) => data.fixed
      case _ => false
    })
    // println(s"setSmartFixed(_, $vi, $state), state1 = $state1")
    vi.setFixed(state1)
  }

  def getVisualData[S <: Sys[S]](vis: Visualization, vi: VisualItem): Option[NuagesData[S]] =
    vis.getRenderer(vi) match {
      case _: NuagesShapeRenderer[_] =>
        val data = vi.get(COL_NUAGES).asInstanceOf[NuagesData[S]]
        Option(data)

      case _ => None
    }

  private final class Drag(val vi: VisualItem, val lastPt: Point2D) {
    var started = false
  }
}
class DragAndMouseDelegateControl[S <: Sys[S]](vis: Visualization) extends ControlAdapter {

  import DragAndMouseDelegateControl._
  import NuagesPanel._

//  private var hoverItem : Option[VisualItem] = None
  private var drag: Option[Drag] = None

  override def itemEntered(vi: VisualItem, e: MouseEvent): Unit = {
    vis.getRenderer(vi) match {
      case pr: NuagesShapeRenderer[_] =>
        val data = vi.get(COL_NUAGES).asInstanceOf[NuagesData[S]]
        if (data != null) {
          val d = getDisplay(e)
          val displayPt = d.getAbsoluteCoordinate(e.getPoint, null)
          data.update(pr.getShape(vi))
          data.itemEntered(vi, e, displayPt)
        }

      case _ =>
    }
    e.getComponent.setCursor(csrHand)
//    hoverItem = Some(vi)
    vi match {
      case ni: NodeItem =>
        // println(s"fixed = true; $ni")
        setFixed(ni, fixed = true)

      case ei: EdgeItem =>
        setFixed(ei.getSourceItem, fixed = true)
        setFixed(ei.getTargetItem, fixed = true)

      case _ =>
    }
  }

  @inline private def getDisplay(e: MouseEvent) = e.getComponent.asInstanceOf[Display]

  override def itemExited(vi: VisualItem, e: MouseEvent): Unit = {
    vis.getRenderer(vi) match {
      case pr: NuagesShapeRenderer[_] =>
        val data = vi.get(COL_NUAGES).asInstanceOf[NuagesData[S]]
        if (data != null) {
          val d = getDisplay(e)
          val displayPt = d.getAbsoluteCoordinate(e.getPoint, null)
          data.update(pr.getShape(vi))
          data.itemExited(vi, e, displayPt)
        }

      case _ =>
    }
//    hoverItem = None
    vi match {
      case ni: NodeItem =>
        // println(s"fixed = false; $ni")
        setFixed(ni, fixed = false)

      case ei: EdgeItem =>
        setFixed(ei.getSourceItem, fixed = false)
        setFixed(ei.getTargetItem, fixed = false)

      case _ =>
    }
    e.getComponent.setCursor(csrDefault)
  }

  override def itemPressed(vi: VisualItem, e: MouseEvent): Unit = {
    val d = getDisplay(e)
    val displayPt = d.getAbsoluteCoordinate(e.getPoint, null)
    vis.getRenderer(vi) match {
      case pr: NuagesShapeRenderer[_] =>
        val data = vi.get(COL_NUAGES).asInstanceOf[NuagesData[S]]
        if (data != null) {
          data.update(pr.getShape(vi))
          if (data.itemPressed(vi, e, displayPt)) return // consumed
          if (e.getClickCount == 2) data.fixed = !data.fixed
        }

      // case er: EdgeRenderer =>
      case _ =>
    }
    if (SwingUtilities.isLeftMouseButton(e) && !e.isShiftDown) {
      val dr = new Drag(vi, displayPt)
      drag = Some(dr)
      // println("drag = Some")
      if (vi.isInstanceOf[AggregateItem]) { // XXX TODO remind me, why this check?
        setFixed(vi, fixed = true)
      }
    }
  }

  override def itemReleased(vi: VisualItem, e: MouseEvent): Unit = {
    vis.getRenderer(vi) match {
      case pr: NuagesShapeRenderer[_] =>
        val data = vi.get(COL_NUAGES).asInstanceOf[NuagesData[S]]
        if (data != null) {
          val d = getDisplay(e)
          val displayPt = d.getAbsoluteCoordinate(e.getPoint, null)
          data.update(pr.getShape(vi))
          data.itemReleased(vi, e, displayPt)
        }

      case _ =>
    }
    drag.foreach { _ =>
      setFixed(vi, fixed = false)
      // println("drag = None")
      drag = None
    }
  }

  override def itemDragged(vi: VisualItem, e: MouseEvent): Unit = {
    val d = getDisplay(e)
    val newPt = d.getAbsoluteCoordinate(e.getPoint, null)
    vis.getRenderer(vi) match {
      case pr: NuagesShapeRenderer[_] =>
        val data = vi.get(COL_NUAGES).asInstanceOf[NuagesData[S]]
        if (data != null) {
          data.update(pr.getShape(vi))
          data.itemDragged(vi, e, newPt)
        }

      case _ =>
    }
    drag.foreach { dr =>
      if (dr.vi == vi) {  // skip orphaned drag
        if (!dr.started) {
          dr.started = true
        }
        val dx = newPt.getX - dr.lastPt.getX
        val dy = newPt.getY - dr.lastPt.getY
        move(vi, dx, dy)
        dr.lastPt.setLocation(newPt)
      } else {
        drag = None // clear orphan
      }
    }
  }

  // recursive over aggregate items
  private def setFixed(vi: VisualItem, fixed: Boolean): Unit =
    vi match {
      case ai: AggregateItem =>
        val it = ai.items()
        while (it.hasNext) {
          val vi2 = it.next.asInstanceOf[VisualItem]
          setFixed(vi2, fixed)
        }

      case _ => setSmartFixed(vis, vi, fixed)
    }

  // recursive over aggregate items
  private def move(vi: VisualItem, dx: Double, dy: Double): Unit = {
    vi match {
      case ai: AggregateItem =>
        val it = ai.items()
        while (it.hasNext) {
          val vi2 = it.next.asInstanceOf[VisualItem]
          move(vi2, dx, dy)
        }

      case _ =>
        val x = vi.getX
        val y = vi.getY
        vi.setStartX(x)
        vi.setStartY(y)
        vi.setX(x + dx)
        vi.setY(y + dy)
        vi.setEndX(x + dx)
        vi.setEndY(y + dy)
    }
  }
}