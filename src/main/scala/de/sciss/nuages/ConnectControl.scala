/*
 *  ConnectControl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2012 Hanns Holger Rutz. All rights reserved.
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

import prefuse.controls.ControlAdapter
import prefuse.visual.{NodeItem, VisualItem}
import java.awt.event.MouseEvent
import prefuse.util.display.PaintListener
import java.awt.{Color, Graphics2D}
import java.awt.geom.{Line2D, Point2D}
import de.sciss.synth.proc.ProcTxn
import prefuse.{Visualization, Display}

class ConnectControl( vis: Visualization )
extends ControlAdapter with PaintListener {
   control =>
   
   import NuagesPanel._

   private case class DragSource( vi: VisualItem, visual: VisualAudioOutput )
//   private case class DragTarget( vi: VisualItem, visual: VisualAudioInput )
   private case class DragTarget( vi: VisualItem, visual: VisualParam )
   private class Drag( val source: DragSource,
                       val targetLoc: Point2D, var target: Option[ DragTarget ])

   private var drag: Option[ Drag ] = None

   def prePaint( d: Display, g: Graphics2D ) {}

   def postPaint( d: Display, g: Graphics2D ) {
      drag.foreach( dr => {
         g.setColor( if( dr.target.isDefined ) Color.green else Color.red )
         val tgtX = dr.target.map( _.vi.getX ).getOrElse( dr.targetLoc.getX )
         val tgtY = dr.target.map( _.vi.getY ).getOrElse( dr.targetLoc.getY )
         val srcX = dr.source.vi.getX
         val srcY = dr.source.vi.getY
         val lin  = new Line2D.Double( srcX, srcY, tgtX, tgtY )
         val trns = d.getTransform
         val shp  = trns.createTransformedShape( lin )
         g.draw( shp )
      })
   }

   override def itemPressed( vi: VisualItem, e: MouseEvent ) {
//      if( !e.isControlDown() ) return
      if( !e.isShiftDown ) return
      vi match {
         case ni: NodeItem => {
            val data = ni.get( COL_NUAGES ).asInstanceOf[ VisualData ]
            if( data == null ) return
            data match {
               case vBus: VisualAudioOutput => {
                  val d          = getDisplay( e )
                  val displayPt  = d.getAbsoluteCoordinate( e.getPoint, null )
                  val dr         = new Drag( DragSource( vi, vBus ), displayPt, None )
                  d.addPaintListener( control )
                  drag           = Some( dr )
               }
               case _ =>
            }
         }
         case _ =>
      }
   }

   /**
    *    Bug in Prefuse: With Ctrl+Mouse we loose
    *    the item. So make sure we continue to track
    *    the motion and eventually kill the edge
    */
   override def mouseMoved( e: MouseEvent ) {
      checkDrag( e )
   }

   /**
    *    Bug in Prefuse: With Ctrl+Mouse we loose
    *    the item. So make sure we continue to track
    *    the motion and eventually kill the edge
    */
   override def mouseReleased( e: MouseEvent ) {
      checkRelease( e )
   }

   override def itemDragged( vi: VisualItem, e: MouseEvent ) {
      checkDrag( e )
   }

   private def checkDrag( e: MouseEvent ) {
      drag.foreach( dr => {
         val d          = getDisplay( e )
         val screenPt   = e.getPoint
         d.getAbsoluteCoordinate( screenPt, dr.targetLoc )
         val vi         = d.findItem( screenPt )
         val tgt        = vi match {
            case ni: NodeItem => {
               if( vi != null ) {
                  val data = vi.get( COL_NUAGES ).asInstanceOf[ VisualData ]
                  if( data == null ) None
                  else data match {
                     case vBus: VisualAudioInput if( vBus.bus.proc != dr.source.visual.bus.proc ) =>
                        Some( DragTarget( vi, vBus ))
                     case vControl: VisualControl if( vControl.mapping == None && vControl.control.isMapable ) =>
                        Some( DragTarget( vi, vControl ))
                     case _ => None
                  }
               } else None
            }
            case _ => None
         }
         if( tgt != dr.target ) {
            dr.target.foreach( t => DragControl.setSmartFixed( vis, t.vi, false ))
            dr.target = tgt
            dr.target.foreach( t => DragControl.setSmartFixed( vis, t.vi, false ))
         }
      })
   }

   override def itemReleased( vi: VisualItem, e: MouseEvent ) {
      checkRelease( e )
   }

   private def checkRelease( e: MouseEvent ) {
      drag.foreach( dr => {
//println( "REMOVE EDGE" )
//         g.removeEdge( edge )
         val d = getDisplay( e )
         d.removePaintListener( control )
         drag = None
         dr.target.foreach( tgt => {
            DragControl.setSmartFixed( vis, tgt.vi, false )
            tgt.visual match {
               case vBus: VisualAudioInput => {
                  ProcTxn.atomic { implicit t => dr.source.visual.bus.~>( vBus.bus )}
               }
               case vControl: VisualControl => {
                  ProcTxn.atomic { implicit t => dr.source.visual.bus.~>( vControl.control )}
               }
//               case _ =>
            }
         })
      })
   }

   @inline private def getDisplay( e: MouseEvent ) = e.getComponent.asInstanceOf[ Display ]
}