/*
 *  ConnectControl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2019 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import java.awt.event.MouseEvent
import java.awt.geom.{Line2D, Point2D}
import java.awt.{Color, Graphics2D}

import de.sciss.lucre.synth.Sys
import prefuse.Display
import prefuse.controls.ControlAdapter
import prefuse.util.display.PaintListener
import prefuse.visual.{NodeItem, VisualItem}

/** A control that draws a rubber band for connecting two nodes. */
object ConnectControl {
  private final case class DragSource[S <: Sys[S]](vi: VisualItem, outputView: NuagesOutput        [S])
  private final case class DragTarget[S <: Sys[S]](vi: VisualItem, inputView: NuagesAttribute.Input[S])

  private final class Drag[S <: Sys[S]](val source: DragSource[S], val targetLoc: Point2D,
                                        var target: Option[DragTarget[S]])
}
class ConnectControl[S <: Sys[S]](main: NuagesPanel[S])
  extends ControlAdapter with PaintListener {
  control =>

  import ConnectControl._
  import NuagesPanel._
  import main.{visualization => vis}

  private var drag: Option[Drag[S]] = None

  def prePaint(d: Display, g: Graphics2D): Unit = ()

  def postPaint(d: Display, g: Graphics2D): Unit = drag.foreach { dr =>
    g.setColor(if (dr.target.isDefined) Color.green else Color.red)
    val tgtX  = dr.target.map(_.vi.getX).getOrElse(dr.targetLoc.getX)
    val tgtY  = dr.target.map(_.vi.getY).getOrElse(dr.targetLoc.getY)
    val srcX  = dr.source.vi.getX
    val srcY  = dr.source.vi.getY
    val lin   = new Line2D.Double(srcX, srcY, tgtX, tgtY)
    val trns  = d.getTransform
    val shp   = trns.createTransformedShape(lin)
    g.draw(shp)
  }

  override def itemPressed(vi: VisualItem, e: MouseEvent): Unit = {
    //      if( !e.isControlDown() ) return
    if (!e.isShiftDown) return
    vi match {
      case ni: NodeItem =>
        val data = ni.get(COL_NUAGES).asInstanceOf[NuagesData[S]]
        if (data == null) return
        data match {
          case vBus: NuagesOutput[S] =>
            val d         = getDisplay(e)
            val displayPt = d.getAbsoluteCoordinate(e.getPoint, null)
            val dr        = new Drag(DragSource(vi, vBus), displayPt, None)
            d.addPaintListener(control)
            drag          = Some(dr)

          case _ =>
        }

      case _ =>
    }
  }

  /** Bug in Prefuse: With Ctrl+Mouse we lose
    * the item. So make sure we continue to track
    * the motion and eventually kill the edge
    */
  override def mouseMoved(e: MouseEvent): Unit = checkDrag(e)

  /** Bug in Prefuse: With Ctrl+Mouse we lose
    * the item. So make sure we continue to track
    * the motion and eventually kill the edge
    */
  override def mouseReleased(e: MouseEvent): Unit = checkRelease(e)

  override def itemDragged(vi: VisualItem, e: MouseEvent): Unit = checkDrag(e)

  private def checkDrag(e: MouseEvent): Unit = drag.foreach { dr =>
    val d         = getDisplay(e)
    val screenPt  = e.getPoint
    d.getAbsoluteCoordinate(screenPt, dr.targetLoc)
    val vi        = d.findItem(screenPt)
    val tgt       = vi match {
      case _: NodeItem =>
        vi.get(COL_NUAGES) match {
          case vCtl: NuagesAttribute.Input[S] if vCtl.attribute.parent != dr.source.outputView.parent =>
            Some(DragTarget(vi, vCtl))
          case _ => None
        }

      case _ => None
    }
    if (tgt != dr.target) {
      dr.target.foreach(t => DragAndMouseDelegateControl.setSmartFixed(vis, t.vi, state = false))
      dr.target = tgt
      dr.target.foreach(t => DragAndMouseDelegateControl.setSmartFixed(vis, t.vi, state = true ))
    }
  }

  override def itemReleased(vi: VisualItem, e: MouseEvent): Unit = checkRelease(e)

  private def checkRelease(e: MouseEvent): Unit = drag.foreach { dr =>
    val d = getDisplay(e)
    d.removePaintListener(control)
    drag = None
    dr.target.foreach { tgt =>
      DragAndMouseDelegateControl.setSmartFixed(vis, tgt.vi, state = false)
      main.cursor.step { implicit tx =>
        val inputView   = tgt.inputView
        val outputView  = dr.source.outputView
        if (!outputView.mappings.contains(inputView)) {
          val inputParent = inputView.inputParent
          val output      = outputView.output
          // inputParent.addChild(output)
          // val _test = inputView.attribute
          // println(s"current value: ${_test.parent.obj.attr.get(_test.key)}")
          if (inputView.attribute.isControl)
            inputParent.updateChild(inputView.input, output, dt = 0L, clearRight = true)
          else
            inputParent.addChild(output)
        }
      }
    }
  }

  @inline private def getDisplay(e: MouseEvent) = e.getComponent.asInstanceOf[Display]
}