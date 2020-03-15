/*
 *  ClickControl.scala
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
import java.awt.geom.{Point2D, Rectangle2D}

import de.sciss.lucre.expr.DoubleVector
import de.sciss.lucre.synth.Sys
import prefuse.Display
import prefuse.controls.ControlAdapter
import prefuse.util.GraphicsLib
import prefuse.util.display.DisplayLib
import prefuse.visual.{EdgeItem, VisualItem}

/** A mouse control for interacting with the display surface
  * as well as with nodes and edges.
  *
  * - meta-click on surface: zoom-to-fit
  * - meta-click on node: zoom to fit node
  * - control-click on surface or node: pan to mouse coordinate
  * - double-click on surface: show generator dialog
  * - double-click on edge   : show filter    dialog
  * - alt-click on edge: delete edge
  */
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
    (nSrc.get(COL_NUAGES), nTgt.get(COL_NUAGES)) match {
      case (srcData: NuagesOutput[S], tgtData: NuagesAttribute.Input[S]) =>
        main.cursor.step { implicit tx =>
          // val inputParent = tgtData.inputParent
          val before      = srcData.output
          val inAttr      = tgtData.attribute
          val inOpt       = srcData.mappings.find(_.attribute == inAttr)
          inOpt.fold[Unit] {
            println(s"Warning: cannot find input for $srcData")
          } { in =>
            // val inputParent = tgtData.inputParent
            val inputParent = in.inputParent
            // println(s"numChildren = ${inputParent.numChildren}")
            if (inputParent.numChildren > 1 /* || !inAttr.isControl */) {
              inputParent.removeChild(before)
            } else {
//            val numCh = NOT: EDT - tgtData.numChannels
//            val now   = if (tgtData.attribute.isControl) {
//              val v = NOT: EDT - tgtData.value
//              if (numCh == 1) DoubleObj.newVar(v.head) else DoubleVector.newVar(v)
//            } else {
//              if (numCh == 1) DoubleObj.newVar(0.0) else DoubleVector.newVar(Vector.fill(numCh)(0.0))
//            }
              val numCh = 2   // XXX TODO
              val now   = DoubleVector.newVar[S](Vector.fill(numCh)(0.0))
              inputParent.updateChild(before, now, dt = 0L, clearRight = true)
            }
          }
        }
        // if (!res) println(s"Warning: could not remove edge")

      case _ =>
    }
  }

  @inline private def getDisplay(e: MouseEvent) = e.getComponent.asInstanceOf[Display]
}