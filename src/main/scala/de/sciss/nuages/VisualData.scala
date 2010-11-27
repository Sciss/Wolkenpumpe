/*
 *  VisualData.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2010 Hanns Holger Rutz. All rights reserved.
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

import java.awt.{Font, Graphics2D, Shape, BasicStroke, Color}
import java.awt.event.MouseEvent
import prefuse.util.ColorLib
import prefuse.data.{Edge, Node => PNode, Graph}
import prefuse.visual.{AggregateItem, VisualItem}
import de.sciss.synth.proc.{Instant, ControlValue, ProcControl, ProcAudioInput, ProcAudioOutput, ControlBusMapping, ProcDiff, ProcFilter, ProcTxn, Proc}
import java.awt.geom.{Line2D, Arc2D, Area, Point2D, Ellipse2D, GeneralPath, Rectangle2D}
import collection.mutable.{ Set => MSet }

object VisualData {
   val diam  = 50
   private val eps   = 1.0e-2

   val colrPlaying   = new Color( 0x00, 0xC0, 0x00 )
   val colrStopped   = new Color( 0x80, 0x80, 0x80 )
   val colrBypassed  = new Color( 0xFF, 0xC0, 0x00 )
   val colrMapped    = new Color( 210, 60, 60 )
   val colrManual    = new Color( 60, 60, 240 )
   val colrGliding   = new Color( 135, 60, 150 )
   val colrAdjust    = new Color( 0xFF, 0xC0, 0x00 )

   val strkThick     = new BasicStroke( 2f )

//      val stateColors   = Array( colrStopped, colrPlaying, colrStopped, colrBypassed )
}

trait VisualData {
   import VisualData._

//      var valid   = false // needs validation first!

   protected val r: Rectangle2D    = new Rectangle2D.Double()
   protected var outline : Shape   = r
   protected val outerE   = new Ellipse2D.Double()
   protected val innerE   = new Ellipse2D.Double()
   protected val margin   = diam * 0.2
   protected val margin2  = margin * 2
   protected val gp       = new GeneralPath()

   protected def main: NuagesPanel

   def update( shp: Shape ) {
      val newR = shp.getBounds2D()
      if( (math.abs( newR.getWidth() - r.getWidth() ) < eps) &&
          (math.abs( newR.getHeight() - r.getHeight() ) < eps) ) {

         r.setFrame( newR.getX(), newR.getY(), r.getWidth(), r.getHeight() )
         return
      }
      r.setFrame( newR )
      outline = shp

      outerE.setFrame( 0, 0, r.getWidth(), r.getHeight() )
      innerE.setFrame( margin, margin, r.getWidth() - margin2, r.getHeight() - margin2 )
      gp.reset()
      gp.append( outerE, false )
      boundsResized
   }

   def render( g: Graphics2D, vi: VisualItem ) {
      g.setColor( ColorLib.getColor( vi.getFillColor ))
      g.fill( outline )
      val atOrig = g.getTransform
      g.translate( r.getX(), r.getY() )
//         g.setRenderingHint( RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON )
      renderDetail( g, vi )
      g.setTransform( atOrig )
   }

   def itemEntered( vi: VisualItem, e: MouseEvent, pt: Point2D ) {}
   def itemExited( vi: VisualItem, e: MouseEvent, pt: Point2D ) {}
   def itemPressed( vi: VisualItem, e: MouseEvent, pt: Point2D ) : Boolean = false
   def itemReleased( vi: VisualItem, e: MouseEvent, pt: Point2D ) {}
   def itemDragged( vi: VisualItem, e: MouseEvent, pt: Point2D ) {}

   protected def drawName( g: Graphics2D, vi: VisualItem, font: Font ) {
      g.setColor( ColorLib.getColor( vi.getTextColor() ))
      if( main.display.isHighQuality ) {
         drawNameHQ( g, vi, font )
      } else {
         val cx   = r.getWidth() / 2
         val cy   = r.getHeight() / 2
         g.setFont( font )
         val fm   = g.getFontMetrics()
         val n    = name
         g.drawString( n, (cx - (fm.stringWidth( n ) * 0.5)).toInt,
                          (cy + ((fm.getAscent() - fm.getLeading()) * 0.5)).toInt )
      }
   }

   private def drawNameHQ( g: Graphics2D, vi: VisualItem, font: Font ) {
      val n    = name
      val v = font.createGlyphVector( g.getFontRenderContext(), n )
      val vvb = v.getVisualBounds()
      val vlb = v.getLogicalBounds()

      // for PDF output, drawGlyphVector gives correct font rendering,
      // while drawString only does with particular fonts.
//         g.drawGlyphVector( v, ((r.getWidth() - vb.getWidth()) * 0.5).toFloat,
//                           ((r.getHeight() + (fm.getAscent() - fm.getLeading())) * 0.5).toFloat )
//         g.drawGlyphVector( v, ((r.getWidth() - vb.getWidth()) * 0.5).toFloat,
//                               ((r.getHeight() - vb.getHeight()) * 0.5).toFloat )
      val shp = v.getOutline( ((r.getWidth() - vvb.getWidth()) * 0.5).toFloat,
                              ((r.getHeight() + vvb.getHeight()) * 0.5).toFloat )
      g.fill( shp )
   }

   def name : String
   protected def boundsResized : Unit
   protected def renderDetail( g: Graphics2D, vi: VisualItem )
}

case class VisualProc( main: NuagesPanel, proc: Proc, pNode: PNode, aggr: AggregateItem,
                       params: Map[ String, VisualParam ]) extends VisualData {
   vproc =>

   import VisualData._

//      var playing = false
   @volatile private var stateVar = Proc.State( false )
   @volatile private var disposeAfterFade = false

   private val playArea = new Area()

   def name : String = proc.name

   def isAlive = stateVar.valid && !disposeAfterFade
   def state = stateVar
   def state_=( value: Proc.State ) {
      stateVar = value
      if( disposeAfterFade && !value.fading ) disposeProc
   }

   override def itemPressed( vi: VisualItem, e: MouseEvent, pt: Point2D ) : Boolean = {
      if( !isAlive ) return false
      if( super.itemPressed( vi, e, pt )) return true

      val xt = pt.getX() - r.getX()
      val yt = pt.getY() - r.getY()
      if( playArea.contains( xt, yt )) {
         ProcTxn.atomic { implicit t =>
            t.withTransition( main.transition( t.time )) {
//println( "AQUI : state = " + state )
               if( stateVar.playing ) {
                  if( proc.anatomy == ProcFilter ) {
//println( "--> BYPASSED = " + !state.bypassed )
                     if( stateVar.bypassed ) proc.engage else proc.bypass
                  } else {
//println( "--> STOP" )
                     proc.stop
                  }
               } else {
//println( "--> PLAY" )
                  proc.play
               }
            }
         }
         true
      } else if( outerE.contains( xt, yt ) & e.isAltDown() ) {
         val instant = !stateVar.playing || stateVar.bypassed || (main.transition( 0 ) == Instant)
         proc.anatomy match {
            case ProcFilter => {
//println( "INSTANT = " + instant + "; PLAYING? " + stateVar.playing + "; BYPASSED? " + stateVar.bypassed )
               ProcTxn.atomic { implicit t =>
                  if( instant ) disposeFilter else {
                     t.afterCommit { _ => disposeAfterFade = true }
                     t.withTransition( main.transition( t.time )) { proc.bypass }
                  }
               }
            }
            case ProcDiff => {
               ProcTxn.atomic { implicit t =>
//                     val toDispose = MSet.empty[ VisualProc ]()
//                     addToDisposal( toDispose, vproc )

                  if( instant ) disposeGenDiff else {
                     t.afterCommit { _ => disposeAfterFade = true }
                     t.withTransition( main.transition( t.time )) { proc.stop }
                  }
               }
            }
            case _ =>
         }
         true
      } else false
   }

   private def addToDisposal( toDispose: MSet[ Proc ], p: Proc )( implicit tx: ProcTxn ) {
      if( toDispose.contains( p )) return
      toDispose += p
      val more = (p.audioInputs.flatMap( _.edges.map( _.sourceVertex )) ++
                  p.audioOutputs.flatMap( _.edges.map( _.targetVertex )))
      more.foreach( addToDisposal( toDispose, _ )) // XXX tail rec
   }

   private def disposeProc {
println( "DISPOSE " + proc )
      ProcTxn.atomic { implicit t =>
         proc.anatomy match {
            case ProcFilter => disposeFilter
            case _ => disposeGenDiff
         }
      }
   }

   private def disposeFilter( implicit tx: ProcTxn ) {
      val in   = proc.audioInput( "in" )
      val out  = proc.audioOutput( "out" )
      val ines = in.edges.toSeq
      val outes= out.edges.toSeq
      if( ines.size > 1 ) println( "WARNING : Filter is connected to more than one input!" )
      outes.foreach( oute => out ~/> oute.in )
      ines.headOption.foreach( ine => {
         outes.foreach( oute => ine.out ~> oute.in )
      })
      // XXX tricky: this needs to be last, so that
      // the pred out's bus isn't set to physical out
      // (which is currently not undone by AudioBusImpl)
      ines.foreach( ine => ine.out ~/> in )
      proc.dispose
   }

   private def disposeGenDiff( implicit tx: ProcTxn ) {
      val toDispose = MSet.empty[ Proc ]
      addToDisposal( toDispose, proc )
      toDispose.foreach( p => {
         val ines = p.audioInputs.flatMap( _.edges ).toSeq // XXX
         val outes= p.audioOutputs.flatMap( _.edges ).toSeq // XXX
         outes.foreach( oute => oute.out ~/> oute.in )
         ines.foreach( ine => ine.out ~/> ine.in )
         p.dispose
      })
   }

   protected def boundsResized {
      val playArc = new Arc2D.Double( 0, 0, r.getWidth(), r.getHeight(), 135, 90, Arc2D.PIE )
      playArea.reset()
      playArea.add( new Area( playArc ))
      playArea.subtract( new Area( innerE ))
      gp.append( playArea, false )
   }

   protected def renderDetail( g: Graphics2D, vi: VisualItem ) {
      if( stateVar.valid ) {
         g.setColor( if( stateVar.playing ) {
               if( stateVar.bypassed ) colrBypassed else colrPlaying
            } else colrStopped
         )
         g.fill( playArea )
      }
//         g.setColor( ColorLib.getColor( vi.getStrokeColor ))
      g.setColor( if( disposeAfterFade ) Color.red else Color.white )
      g.draw( gp )

      val font = Wolkenpumpe.condensedFont.deriveFont( diam * vi.getSize().toFloat * 0.33333f )
      drawName( g, vi, font )
   }
}


trait VisualParam extends VisualData {
//      def param: ProcParam[ _ ]
   def pNode: PNode
   def pEdge: Edge
}

//   private[nuages] case class VisualBus( param: ProcParamAudioBus, pNode: PNode, pEdge: Edge )
private[nuages] case class VisualAudioInput( main: NuagesPanel, bus: ProcAudioInput, pNode: PNode, pEdge: Edge )
extends VisualParam {
   import VisualData._

   def name : String = bus.name

   protected def boundsResized {}

   protected def renderDetail( g: Graphics2D, vi: VisualItem ) {
      val font = Wolkenpumpe.condensedFont.deriveFont( diam * vi.getSize().toFloat * 0.5f )
      drawName( g, vi, font )
   }
}

private[nuages] case class VisualAudioOutput( main: NuagesPanel, bus: ProcAudioOutput, pNode: PNode, pEdge: Edge )
extends VisualParam {
   import VisualData._

   def name : String = bus.name

   protected def boundsResized {}

   protected def renderDetail( g: Graphics2D, vi: VisualItem ) {
      val font = Wolkenpumpe.condensedFont.deriveFont( diam * vi.getSize().toFloat * 0.5f )
      drawName( g, vi, font )
   }
}

private[nuages] case class VisualMapping( mapping: ControlBusMapping, pEdge: Edge )

private[nuages] case class VisualControl( control: ProcControl, pNode: PNode, pEdge: Edge )( vProc: => VisualProc )
extends VisualParam {
   import VisualData._

   var value : ControlValue = null
//      var mapped  = false
   var gliding = false
   var mapping : Option[ VisualMapping ] = None

   private var renderedValue  = Double.NaN
   private val containerArea  = new Area()
   private val valueArea      = new Area()

   def name : String = control.name
   def main : NuagesPanel = vProc.main

   private var drag : Option[ Drag ] = None

//      private def isValid = vProc.isValid

   override def itemPressed( vi: VisualItem, e: MouseEvent, pt: Point2D ) : Boolean = {
      if( !vProc.isAlive ) return false
//         if( super.itemPressed( vi, e, pt )) return true

      if( containerArea.contains( pt.getX() - r.getX(), pt.getY() - r.getY() )) {
         val dy   = r.getCenterY() - pt.getY()
         val dx   = pt.getX() - r.getCenterX()
         val ang  = math.max( 0.0, math.min( 1.0, (((-math.atan2( dy, dx ) / math.Pi + 3.5) % 2.0) - 0.25) / 1.5 ))
         val instant = !vProc.state.playing || vProc.state.bypassed || main.transition( 0 ) == Instant
         val vStart = if( e.isAltDown() ) {
//               val res = math.min( 1.0f, (((ang / math.Pi + 3.25) % 2.0) / 1.5).toFloat )
//               if( ang != value ) {
               val m    = control.spec.map( ang )
               if( instant ) setControl( control, m, true )
//               }
            ang
         } else control.spec.unmap( value.currentApprox )
         val dr = Drag( ang, vStart, instant )
         drag = Some( dr )
         true
      } else false
   }

   private def setControl( c: ProcControl, v: Double, instant: Boolean ) {
      ProcTxn.atomic { implicit t =>
         if( instant ) {
            c.v = v
         } else t.withTransition( main.transition( t.time )) {
            c.v = v
         }
      }
   }

   override def itemDragged( vi: VisualItem, e: MouseEvent, pt: Point2D ) {
      drag.foreach( dr => {
         val dy   = r.getCenterY() - pt.getY()
         val dx   = pt.getX() - r.getCenterX()
//            val ang  = -math.atan2( dy, dx )
         val ang  = (((-math.atan2( dy, dx ) / math.Pi + 3.5) % 2.0) - 0.25) / 1.5
         val vEff = math.max( 0.0, math.min( 1.0, dr.valueStart + (ang - dr.angStart) ))
//            if( vEff != value ) {
            val m    = control.spec.map( vEff )
            if( dr.instant ) {
               setControl( control, m, true )
            } else {
               dr.dragValue = m
            }
//            }
      })
   }

   override def itemReleased( vi: VisualItem, e: MouseEvent, pt: Point2D ) {
      drag foreach { dr =>
         if( !dr.instant ) {
            setControl( control, dr.dragValue, false )
         }
         drag = None
      }
   }

   protected def boundsResized {
      val pContArc = new Arc2D.Double( 0, 0, r.getWidth(), r.getHeight(), -45, 270, Arc2D.PIE )
      containerArea.reset()
      containerArea.add( new Area( pContArc ))
      containerArea.subtract( new Area( innerE ))
      gp.append( containerArea, false )
      renderedValue = Double.NaN   // triggers updateRenderValue
   }

   private def updateRenderValue( v: Double ) {
      renderedValue  = v
      val vn         = control.spec.unmap( v )
//println( "updateRenderValue( " + control.name + " ) from " + v + " to " + vn )
      val angExtent  = (vn * 270).toInt
      val angStart   = 225 - angExtent
      val pValArc    = new Arc2D.Double( 0, 0, r.getWidth(), r.getHeight(), angStart, angExtent, Arc2D.PIE )
      valueArea.reset()
      valueArea.add( new Area( pValArc ))
      valueArea.subtract( new Area( innerE ))
   }

   protected def renderDetail( g: Graphics2D, vi: VisualItem ) {
      if( vProc.state.valid ) {
         val v = value.currentApprox
         if( renderedValue != v ) {
            updateRenderValue( v )
         }
         g.setColor( if( mapping.isDefined ) colrMapped else if( gliding ) colrGliding else colrManual )
         g.fill( valueArea )
         drag foreach { dr => if( !dr.instant ) {
            g.setColor( colrAdjust )
            val ang  = ((1.0 - control.spec.unmap( dr.dragValue )) * 1.5 - 0.25) * math.Pi
//               val cx   = diam * 0.5 // r.getCenterX
//               val cy   = diam * 0.5 // r.getCenterY
            val cos  = math.cos( ang )
            val sin  = math.sin( ang )
//               val lin  = new Line2D.Double( cx + cos * diam*0.5, cy - sin * diam*0.5,
//                                             cx + cos * diam*0.3, cy - sin * diam*0.3 )
            val diam05 = diam * 0.5
            val x0 = (1 + cos) * diam05
            val y0 = (1 - sin) * diam05
            val lin  = new Line2D.Double( x0, y0,
                                          x0 - (cos * diam * 0.2), y0 + (sin * diam * 0.2) )
            val strkOrig = g.getStroke
            g.setStroke( strkThick )
            g.draw( lin )
            g.setStroke( strkOrig )
         }}
      }
      g.setColor( ColorLib.getColor( vi.getStrokeColor ))
      g.draw( gp )

      val font = Wolkenpumpe.condensedFont.deriveFont( diam * vi.getSize().toFloat * 0.33333f )
      drawName( g, vi, font )
   }

   private case class Drag( angStart: Double, valueStart: Double, instant: Boolean ) {
      var dragValue = valueStart
   }
}
