/*
 *  ConnectControl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2014 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.lucre.synth.Sys
import de.sciss.synth.proc.{Scan, Proc}
import prefuse.controls.ControlAdapter
import prefuse.visual.{NodeItem, VisualItem}
import java.awt.event.MouseEvent
import prefuse.util.display.PaintListener
import java.awt.{Color, Graphics2D}
import java.awt.geom.{Line2D, Point2D}
import prefuse.Display

object ConnectControl {
  private final case class DragSource[S <: Sys[S]](vi: VisualItem, visual: VisualScan[S])

  private final case class DragTarget[S <: Sys[S]](vi: VisualItem, visual: VisualParam[S])

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

  def prePaint(d: Display, g: Graphics2D) = ()

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
        val data = ni.get(COL_NUAGES).asInstanceOf[VisualData[S]]
        if (data == null) return
        data match {
          case vBus: VisualScan[S] =>
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

  /** Bug in Prefuse: With Ctrl+Mouse we loose
    * the item. So make sure we continue to track
    * the motion and eventually kill the edge
    */
  override def mouseMoved(e: MouseEvent): Unit = checkDrag(e)

  /** Bug in Prefuse: With Ctrl+Mouse we loose
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
      case ni: NodeItem =>
        val data = vi.get(COL_NUAGES).asInstanceOf[VisualData[S]]
        if (data == null) None
        else data match {
          case vBus: VisualScan[S] if vBus.parent != dr.source.visual.parent =>
            Some(DragTarget(vi, vBus))
//          case vControl: VisualControl if vControl.mapping == None && vControl.control.isMapable =>
//            Some(DragTarget(vi, vControl))
          case _ => None
        }

      case _ => None
    }
    if (tgt != dr.target) {
      dr.target.foreach(t => DragControl.setSmartFixed(vis, t.vi, state = false))
      dr.target = tgt
      dr.target.foreach(t => DragControl.setSmartFixed(vis, t.vi, state = false))
    }
  }

  override def itemReleased(vi: VisualItem, e: MouseEvent): Unit = checkRelease(e)

   private def checkRelease( e: MouseEvent ): Unit = drag.foreach { dr =>
     //println( "REMOVE EDGE" )
     //         g.removeEdge( edge )
     val d = getDisplay(e)
     d.removePaintListener(control)
     drag = None
     dr.target.foreach { tgt =>
       DragControl.setSmartFixed(vis, tgt.vi, state = false)
       tgt.visual match {
         case tgtVScan: VisualScan[S] =>
           main.cursor.step { implicit tx =>
             val srcVScan = dr.source.visual
             val srcObj   = srcVScan.parent.objH()
             val tgtObj   = tgtVScan.parent.objH()
             for {
               srcProc <- Proc.Obj.unapply(srcObj)
               tgtProc <- Proc.Obj.unapply(tgtObj)
               srcScan <- srcProc.elem.peer.scans.get(srcVScan.key)
               tgtScan <- tgtProc.elem.peer.scans.get(tgtVScan.key)
             } {
               srcScan.addSink(Scan.Link.Scan(tgtScan))
             }
           }

//         case vControl: VisualControl =>
//           ProcTxn.atomic { implicit t => dr.source.visual.bus.~>(vControl.control)}
       }
     }
   }

  @inline private def getDisplay(e: MouseEvent) = e.getComponent.asInstanceOf[Display]
}