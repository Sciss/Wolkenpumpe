/*
 *  ClickControl.scala
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
      //         val d          = getDisplay( e )
      //         val displayPt  = d.getAbsoluteCoordinate( e.getPoint, null )
//      main.actions.showCreateGenDialog(e.getPoint)
      //         insertProc( e )
    }
  }

  //   private def insertProc( e: MouseEvent ) {
  //      (main.genFactory, main.diffFactory) match {
  //         case (Some( genF ), Some( diffF )) => {
  //            val d          = getDisplay( e )
  //            val displayPt  = d.getAbsoluteCoordinate( e.getPoint, null )
  //            createProc( genF, diffF, displayPt )
  //         }
  //         case _ =>
  //      }
  //   }

  override def itemPressed(vi: VisualItem, e: MouseEvent): Unit = {
    if (e.isAltDown) return
    if (e.isMetaDown) {
      zoom(e, vi.getBounds)
    } else if (e.getClickCount == 2) doubleClick(vi, e)
    //      if( e.isAltDown() ) altClick( vi, e )
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
            if (srcData != null && tgtData != null) {
              (srcData, tgtData) match {
//                case (vOut: VisualAudioOutput, vIn: VisualAudioInput) =>
//                  main.actions.showCreateFilterDialog(nSrc, nTgt, vOut.bus, vIn.bus, e.getPoint)
                //                           main.filterFactory foreach { filterF =>
                //                              val d          = getDisplay( e )
                //                              val displayPt  = d.getAbsoluteCoordinate( e.getPoint, null )
                //                              nSrc.setFixed( false ) // XXX woops.... we have to clean up the mess of ConnectControl
                //                              nTgt.setFixed( false )
                //                              createFilter( vOut.bus, vIn.bus, filterF, displayPt )
                //                           }
                case _ =>
              }
            }

          case _ =>
        }

      case _ =>
    }

  @inline private def getDisplay(e: MouseEvent) = e.getComponent.asInstanceOf[Display]
}