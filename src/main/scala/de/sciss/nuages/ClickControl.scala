/*
 *  ClickControl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2011 Hanns Holger Rutz. All rights reserved.
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
 *
 *
 *  Changelog:
 */

package de.sciss.nuages

import prefuse.controls.ControlAdapter
import java.awt.event.MouseEvent
import javax.swing.{ListModel, ListSelectionModel}
import prefuse.{Visualization, Display}
import de.sciss.synth.proc._
import DSL._
import prefuse.visual.{NodeItem, VisualItem, EdgeItem}
import de.sciss.synth.control
import prefuse.util.GraphicsLib
import prefuse.util.display.DisplayLib
import java.awt.geom.{Rectangle2D, Point2D}

/**
 *    Simple interface to query currently selected
 *    proc factory and to feedback on-display positions
 *    for newly created procs.
 * 
 *    Methods are guaranteed to be called in the awt
 *    event thread.
 *
 *    @version 0.11, 14-Jul-10  
 */
trait ProcFactoryProvider {
   def genFactory:      Option[ ProcFactory ]
   def filterFactory:   Option[ ProcFactory ]
   def diffFactory:     Option[ ProcFactory ]
   def collector:       Option[ Proc ]
   def setLocationHint( p: Proc, loc: Point2D )
}

class ClickControl( main: NuagesPanel ) extends ControlAdapter {
   import NuagesPanel._

   override def mousePressed( e: MouseEvent ) {
      if( e.isMetaDown() ) {
         zoomToFit( e )
      } else if( e.getClickCount() == 2 ) insertProc( e )
   }

   private def insertProc( e: MouseEvent ) {
      (main.genFactory, main.diffFactory) match {
         case (Some( genF ), Some( diffF )) => {
            val d          = getDisplay( e )
            val displayPt  = d.getAbsoluteCoordinate( e.getPoint(), null )
            createProc( genF, diffF, displayPt )
         }
         case _ =>
      }
   }

   private def createProc( genF: ProcFactory, diffF: ProcFactory, pt: Point2D ) {
      ProcTxn.atomic { implicit tx =>
         val gen  = genF.make
         val diff = diffF.make
         gen ~> diff
         if( diff.anatomy == ProcFilter ) { // this is the case for config.collector == true
            main.collector.foreach( diff ~> _ )
         }

         tx.beforeCommit { _ =>
            val genPt  = new Point2D.Double( pt.getX, pt.getY - 30 )
            val diffPt = new Point2D.Double( pt.getX, pt.getY + 30 )
            main.setLocationHint( gen, genPt )
            main.setLocationHint( diff, diffPt )
         }
      }
   }

   override def itemPressed( vi: VisualItem, e: MouseEvent ) {
      if( e.isAltDown() ) return
      if( e.isMetaDown() ) {
         zoom( e, vi.getBounds() )
      } else if( e.getClickCount() == 2 ) doubleClick( vi, e )
//      if( e.isAltDown() ) altClick( vi, e )
   }

   private def zoomToFit( e: MouseEvent ) {
      val d       = getDisplay( e )
      val vis     = d.getVisualization()
      val bounds  = vis.getBounds( NuagesPanel.GROUP_GRAPH )
      zoom( e, bounds )
   }

   private def zoom( e: MouseEvent, bounds: Rectangle2D ) {
      val d = getDisplay( e )
      if( d.isTranformInProgress() ) return
      val margin     = 50   // XXX could be customized
      val duration   = 1000 // XXX could be customized
      GraphicsLib.expand( bounds, margin + (1 / d.getScale()).toInt )
      DisplayLib.fitViewToBounds( d, bounds, duration )
   }

   private def doubleClick( vi: VisualItem, e: MouseEvent ) {
      vi match {
         case ei: EdgeItem => {
            val nSrc = ei.getSourceItem
            val nTgt = ei.getTargetItem
            (main.vis.getRenderer( nSrc ), main.vis.getRenderer( nTgt )) match {
               case (_: NuagesProcRenderer, _: NuagesProcRenderer) => {
                  val srcData = nSrc.get( COL_NUAGES ).asInstanceOf[ VisualData ]
                  val tgtData = nTgt.get( COL_NUAGES ).asInstanceOf[ VisualData ]
                  if( srcData != null && tgtData != null ) {
                     (srcData, tgtData) match {
                        case (vOut: VisualAudioOutput, vIn: VisualAudioInput) => main.filterFactory foreach { filterF =>
                           val d          = getDisplay( e )
                           val displayPt  = d.getAbsoluteCoordinate( e.getPoint(), null )
                           nSrc.setFixed( false ) // XXX woops.... we have to clean up the mess of ConnectControl
                           nTgt.setFixed( false )
                           createFilter( vOut.bus, vIn.bus, filterF, displayPt )
                        }
                        case _ =>
                     }
                  }
               }
               case _ =>
            }
         }
         case _ =>
      }
   }

   private def createFilter( out: ProcAudioOutput, in: ProcAudioInput, filterF: ProcFactory, pt: Point2D ) {
//      println( "CREATE FILTER " + out.name + " ~| " + filterF.name + " |> " + in.bus.name )
      ProcTxn.atomic { implicit tx =>
         val filter  = filterF.make
         filter.bypass
         out ~|filter|> in
         if( !filter.isPlaying ) filter.play
         tx.beforeCommit { _ =>
            main.setLocationHint( filter, pt )
         }
//         tx.withTransition( main.transition( tx.time )) {
//            out ~|filter|> in
//         }
      }
   }

   @inline private def getDisplay( e: MouseEvent ) = e.getComponent().asInstanceOf[ Display ]
}