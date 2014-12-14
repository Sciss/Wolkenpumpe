/*
 *  ClickControl.scala
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

import de.sciss.lucre.stm
import de.sciss.lucre.synth.Sys
import prefuse.controls.ControlAdapter
import java.awt.event.MouseEvent
import prefuse.Display
import de.sciss.synth.proc._
import prefuse.visual.{VisualItem, EdgeItem}
import prefuse.util.GraphicsLib
import prefuse.util.display.DisplayLib
import java.awt.geom.{Rectangle2D, Point2D}

/** Simple interface to query currently selected
  * proc factory and to feedback on-display positions
  * for newly created procs.
  *
  * Methods are guaranteed to be called in the awt
  * event thread.
  */
trait ProcFactoryProvider[S <: Sys[S]] {
  type PrH = stm.Source[S#Tx, Obj[S]]

  def genFactory:      Option[PrH]
  def filterFactory:   Option[PrH]
  def diffFactory:     Option[PrH]
  def collector:       Option[PrH]

  def setLocationHint(p: Obj[S], loc: Point2D)(implicit tx: S#Tx): Unit
}

class ClickControl[S <: Sys[S]](main: NuagesPanel[S]) extends ControlAdapter {

  import NuagesPanel._

  override def mousePressed(e: MouseEvent): Unit = {
    if (e.isMetaDown) {
      zoomToFit(e)
    } else if (e.getClickCount == 2) {
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
          case (_: NuagesProcRenderer[_], _: NuagesProcRenderer[_]) =>
            val srcData = nSrc.get(COL_NUAGES).asInstanceOf[VisualData[S]]
            val tgtData = nTgt.get(COL_NUAGES).asInstanceOf[VisualData[S]]
            if (srcData != null && tgtData != null)
              (srcData, tgtData) match {
                case (vOut: VisualScan[S], vIn: VisualScan[S]) =>
                  main.showCreateFilterDialog(/* nSrc, nTgt, */ vOut, vIn, e.getPoint)
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
      case (_: NuagesProcRenderer[_], _: NuagesProcRenderer[_]) =>
        val srcData = nSrc.get(COL_NUAGES).asInstanceOf[VisualData[S]]
        val tgtData = nTgt.get(COL_NUAGES).asInstanceOf[VisualData[S]]
        if (srcData != null && tgtData != null)
          (srcData, tgtData) match {
            case (srcVScan: VisualScan[S], tgtVScan: VisualScan[S]) =>
              main.cursor.step { implicit tx =>
                val srcObj   = srcVScan.parent.objH()
                val tgtObj   = tgtVScan.parent.objH()
                for {
                  srcProc <- Proc.Obj.unapply(srcObj)
                  tgtProc <- Proc.Obj.unapply(tgtObj)
                  srcScan <- srcProc.elem.peer.scans.get(srcVScan.key)
                  tgtScan <- tgtProc.elem.peer.scans.get(tgtVScan.key)
                } {
                  srcScan.removeSink(Scan.Link.Scan(tgtScan))
                }
              }
            case _ =>
          }

      case _ =>
    }
  }

  @inline private def getDisplay(e: MouseEvent) = e.getComponent.asInstanceOf[Display]
}