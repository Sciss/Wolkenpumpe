/*
 *  DragControl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2013 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either
 *  version 2, june 1991 of the License, or (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public
 *  License (gpl.txt) along with this software; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.lucre.synth.Sys
import prefuse.controls.ControlAdapter
import javax.swing.SwingUtilities
import java.awt.Cursor
import java.awt.event.MouseEvent
import java.awt.geom.Point2D
import prefuse.{Visualization, Display}
import prefuse.render.EdgeRenderer
import prefuse.visual.{EdgeItem, NodeItem, AggregateItem, VisualItem}

object DragControl {

  import NuagesPanel._

  private val csrHand     = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
  private val csrDefault  = Cursor.getDefaultCursor

  def setSmartFixed(vis: Visualization, vi: VisualItem, state: Boolean): Unit = ()
//    vi.setFixed(state || getVisualData(vis, vi) match {
//      case Some(vProc: VisualProc) if vProc.proc.anatomy == ProcDiff => true
//      case _ => false
//    })

  // XXX QUE MIERDA
  //   def getVisualData( vi: VisualItem ) : Option[ VisualData ] = {
  //      if( vi.canGet( COL_NUAGES, classOf[ VisualData ])) {
  //         val data = vi.get( COL_NUAGES ).asInstanceOf[ VisualData ]
  //         if( data != null ) Some( data ) else None
  //      } else None
  //   }

  def getVisualData[S <: Sys[S]](vis: Visualization, vi: VisualItem): Option[VisualData[S]] =
    vis.getRenderer(vi) match {
      case pr: NuagesProcRenderer[_] =>
        val data = vi.get(COL_NUAGES).asInstanceOf[VisualData[S]]
        if (data != null) Some(data) else None

      case _ => None
    }

  private final class Drag(/* val vi: VisualItem,*/ val lastPt: Point2D) {
    var started = false
  }
}
class DragControl[S <: Sys[S]](vis: Visualization) extends ControlAdapter {

  import DragControl._
  import NuagesPanel._

  private var hoverItem : Option[VisualItem] = None
  private var drag      : Option[Drag      ] = None

  override def itemEntered(vi: VisualItem, e: MouseEvent): Unit = {
    vis.getRenderer(vi) match {
      case pr: NuagesProcRenderer[_] =>
        val data = vi.get(COL_NUAGES).asInstanceOf[VisualData[S]]
        if (data != null) {
          val d = getDisplay(e)
          val displayPt = d.getAbsoluteCoordinate(e.getPoint, null)
          data.update(pr.getShape(vi))
          data.itemEntered(vi, e, displayPt)
        }

      case _ =>
    }
    e.getComponent.setCursor(csrHand)
    hoverItem = Some(vi)
    vi match {
      case ni: NodeItem =>
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
      case pr: NuagesProcRenderer[_] =>
        val data = vi.get(COL_NUAGES).asInstanceOf[VisualData[S]]
        if (data != null) {
          val d = getDisplay(e)
          val displayPt = d.getAbsoluteCoordinate(e.getPoint, null)
          data.update(pr.getShape(vi))
          data.itemExited(vi, e, displayPt)
        }

      case _ =>
    }
    hoverItem = None
    vi match {
      case ni: NodeItem =>
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
      case pr: NuagesProcRenderer[_] =>
        val data = vi.get(COL_NUAGES).asInstanceOf[VisualData[S]]
        if (data != null) {
          data.update(pr.getShape(vi))
          if (data.itemPressed(vi, e, displayPt)) return // consumed
        }

      // case er: EdgeRenderer =>
      case _ =>
    }
    if (!SwingUtilities.isLeftMouseButton(e) || e.isShiftDown) return
    //      if( e.isAltDown() ) {
    //         vi match {
    //
    //         }
    //      }
    val dr = new Drag(displayPt)
    drag = Some(dr)
    if (vi.isInstanceOf[AggregateItem]) setFixed(vi, fixed = true)
  }

  override def itemReleased(vi: VisualItem, e: MouseEvent): Unit = {
    vis.getRenderer(vi) match {
      case pr: NuagesProcRenderer[_] =>
        val data = vi.get(COL_NUAGES).asInstanceOf[VisualData[S]]
        if (data != null) {
          val d = getDisplay(e)
          val displayPt = d.getAbsoluteCoordinate(e.getPoint, null)
          data.update(pr.getShape(vi))
          data.itemReleased(vi, e, displayPt)
        }

      case _ =>
    }
    drag.foreach { dr =>
      setFixed(vi, fixed = false)
      drag = None
    }
  }

  override def itemDragged(vi: VisualItem, e: MouseEvent): Unit = {
    val d = getDisplay(e)
    val newPt = d.getAbsoluteCoordinate(e.getPoint, null)
    vis.getRenderer(vi) match {
      case pr: NuagesProcRenderer[_] =>
        val data = vi.get(COL_NUAGES).asInstanceOf[VisualData[S]]
        if (data != null) {
          data.update(pr.getShape(vi))
          data.itemDragged(vi, e, newPt)
        }

      case _ =>
    }
    drag.foreach { dr =>
      if (!dr.started) dr.started = true
      val dx = newPt.getX - dr.lastPt.getX
      val dy = newPt.getY - dr.lastPt.getY
      move(vi, dx, dy)
      dr.lastPt.setLocation(newPt)
    }
  }

  // recursive over aggregate items
  private def setFixed(vi: VisualItem, fixed: Boolean): Unit =
    vi match {
      case ai: AggregateItem =>
        val iter = ai.items()
        while (iter.hasNext) {
          val vi2 = iter.next.asInstanceOf[VisualItem]
          setFixed(vi2, fixed)
        }

      case _ => setSmartFixed(vis, vi, fixed)
    }

  // recursive over aggregate items
  private def move(vi: VisualItem, dx: Double, dy: Double): Unit = {
    vi match {
      case ai: AggregateItem =>
        val iter = ai.items()
        while (iter.hasNext) {
          val vi2 = iter.next.asInstanceOf[VisualItem]
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