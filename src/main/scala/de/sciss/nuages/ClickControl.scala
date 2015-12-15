/*
 *  ClickControl.scala
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

import java.awt.event.MouseEvent
import java.awt.geom.{Point2D, Rectangle2D}

import de.sciss.lucre.synth.Sys
import prefuse.Display
import prefuse.controls.ControlAdapter
import prefuse.util.GraphicsLib
import prefuse.util.display.DisplayLib
import prefuse.visual.{EdgeItem, VisualItem}

class ClickControl[S <: Sys[S]](main: NuagesPanel[S]) extends ControlAdapter {

  import NuagesPanel._

  override def mousePressed(e: MouseEvent): Unit = {
    if (e.isMetaDown) {
      zoomToFit(e)
    } else
    if (e.isControlDown) {
      val d  = getDisplay(e)
      val pt = d.getInverseTransform.transform(e.getPoint, null)
      pan(e, pt)
    } else
    if (e.getClickCount == 2) {
      main.showCreateGenDialog(e.getPoint)
    }
  }

  override def itemPressed(vi: VisualItem, e: MouseEvent): Unit = {
    if (e.isAltDown) {
      vi match {
        case ei: EdgeItem => deleteEdge(ei)
        case _ =>
      }
      return
    }
    if (e.isMetaDown) {
      zoom(e, vi.getBounds)
    } else if (e.isControlDown) {
      val b = vi.getBounds
      pan(e, new Point2D.Double(b.getCenterX, b.getCenterY))
    } else {
      if (e.getClickCount == 2) doubleClick(vi, e)
    }
  }

  private def zoomToFit(e: MouseEvent): Unit = {
    val d       = getDisplay(e)
    val vis     = d.getVisualization
    val bounds  = vis.getBounds(NuagesPanel.GROUP_GRAPH)
    zoom(e, bounds)
  }

  private def pan(e: MouseEvent, pt: Point2D): Unit = {
    val d = getDisplay(e)
    if (d.isTranformInProgress) return
    val duration  = 1000 // XXX could be customized
    d.animatePanToAbs(pt, duration)
  }

  private def zoom(e: MouseEvent, bounds: Rectangle2D): Unit = {
    val d = getDisplay(e)
    if (d.isTranformInProgress) return
    val margin    = 50 // XXX could be customized
    val duration  = 1000 // XXX could be customized
    GraphicsLib.expand(bounds, margin + (1 / d.getScale).toInt)
    DisplayLib.fitViewToBounds(d, bounds, duration)
  }

  private def doubleClick(vi: VisualItem, e: MouseEvent): Unit =
    vi match {
      case ei: EdgeItem =>
        val nSrc = ei.getSourceItem
        val nTgt = ei.getTargetItem
        val vis  = main.visualization
        (vis.getRenderer(nSrc), vis.getRenderer(nTgt)) match {
          case (_: NuagesShapeRenderer[_], _: NuagesShapeRenderer[_]) =>
            val srcData = nSrc.get(COL_NUAGES).asInstanceOf[NuagesData[S]]
            val tgtData = nTgt.get(COL_NUAGES).asInstanceOf[NuagesData[S]]
            if (srcData != null && tgtData != null)
              (srcData, tgtData) match {
                case (vOut: NuagesOutput[S], vIn: NuagesAttribute[S]) =>
                  main.showInsertFilterDialog(vOut, vIn, e.getPoint)
                case _ =>
              }

          case _ =>
        }

      case _ =>
    }

  private def deleteEdge(ei: EdgeItem): Unit = {
    val nSrc = ei.getSourceItem
    val nTgt = ei.getTargetItem
    val vis  = main.visualization
    (vis.getRenderer(nSrc), vis.getRenderer(nTgt)) match {
      case (_: NuagesShapeRenderer[_], _: NuagesShapeRenderer[_]) =>
        val srcData = nSrc.get(COL_NUAGES).asInstanceOf[NuagesData[S]]
        val tgtData = nTgt.get(COL_NUAGES).asInstanceOf[NuagesData[S]]
        ???! // SCAN
//        if (srcData != null && tgtData != null)
//          (srcData, tgtData) match {
//            case (srcVScan: VisualScan[S], tgtVScan: VisualScan[S]) =>
//              main.cursor.step { implicit tx =>
//                (srcVScan.parent.obj, tgtVScan.parent.obj) match {
//                  case (srcProc: Proc[S], tgtProc: Proc[S]) =>
//                    for {
//                      srcScan <- srcProc.outputs.get(srcVScan.key)
//                      tgtScan <- tgtProc.inputs .get(tgtVScan.key)
//                    } {
//                      srcScan.remove(Scan.Link.Scan(tgtScan))
//                    }
//                  case _ =>
//                }
//              }
//
//            case (srcVScan: VisualScan[S], tgtCtl: VisualControl[S]) =>
//              main.cursor.step { implicit tx =>
//                tgtCtl.mapping.foreach { m =>
//                  // make sure there are no more /tr updates
//                  m.synth.swap(None)(tx.peer).foreach(_.dispose())
//                }
//                // this causes AttrRemoved and AttrAdded succession
//                tgtCtl.removeMapping()
//              }
//
//            case _ =>
//          }

      case _ =>
    }
  }

  @inline private def getDisplay(e: MouseEvent) = e.getComponent.asInstanceOf[Display]
}