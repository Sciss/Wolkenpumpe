/*
 *  NuagesPanel.scala
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

import javax.swing.JPanel
import collection.breakOut
import collection.mutable.{ Set => MSet }
import collection.immutable.{ IndexedSeq => IIdxSeq, IntMap }
import prefuse.{Constants, Display, Visualization}
import prefuse.action.{RepaintAction, ActionList}
import prefuse.action.animate.{VisibilityAnimator, LocationAnimator, ColorAnimator}
import prefuse.action.layout.graph.{ForceDirectedLayout, NodeLinkTreeLayout}
import prefuse.activity.Activity
import prefuse.util.ColorLib
import prefuse.visual.sort.TreeDepthItemSorter
import prefuse.visual.expression.InGroupPredicate
import de.sciss.synth.{Model, Server}
import prefuse.data.{Edge, Node => PNode, Graph}
import prefuse.controls._
import de.sciss.synth.proc._
import prefuse.render._
import prefuse.action.assignment.{FontAction, ColorAction}
import java.awt._
import event.{ ActionEvent, ActionListener, MouseEvent }
import geom._
import prefuse.visual.{NodeItem, AggregateItem, VisualItem}
import java.util.TimerTask

/**
 *    @version 0.11, 21-Jul-10
 */
object NuagesPanel {
   var verbose = false

   val GROUP_GRAPH          = "graph"
   val GROUP_NODES          = "graph.nodes"
   val GROUP_EDGES          = "graph.edges"

   private[nuages] object VisualData {
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

   private[nuages] trait VisualData {
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

   private[nuages] case class VisualProc( main: NuagesPanel, proc: Proc, pNode: PNode, aggr: AggregateItem,
                                          params: Map[ String, VisualParam ]) extends VisualData {
      vproc =>

      import VisualData._

//      var playing = false
      private var stateVar = Proc.State( false )
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

   private[nuages] trait VisualParam extends VisualData {
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

   private[nuages] val COL_NUAGES = "nuages"
}

class NuagesPanel( server: Server ) extends JPanel
with ProcFactoryProvider {
   panel =>
   
   import NuagesPanel._
   import ProcWorld._
   import Proc._

   val vis     = new Visualization()
   val world   = ProcDemiurg.worlds( server )
   val display = new Display( vis ) {
//      override protected def setRenderingHints( g: Graphics2D ) {
//         super.setRenderingHints( g )
//         g.setRenderingHint( RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON )
//      }
   }

   private val AGGR_PROC            = "aggr"
//   private val GROUP_PAUSED         = "paused"
   private val ACTION_LAYOUT        = "layout"
   private val ACTION_LAYOUT_ANIM   = "layout-anim"
   private val ACTION_COLOR         = "color"
//   private val ACTION_EDGECOLOR     = "edgecolor"
   private val ACTION_COLOR_ANIM    = "layout-anim"
   private val FADE_TIME            = 333
//   private val COL_LABEL            = "name"
//   private val GROUP_PROC           = "proc"

   val g                    = {
      val res     = new Graph
      val nodes   = res.getNodeTable()
//      res.addColumn( COL_LABEL, classOf[ String ])
//val test = res.addNode()
//test.set( COL_LABEL, "HALLLLLO" )
      res
   }
   val vg   = {
      val res = vis.addGraph( GROUP_GRAPH, g )
      res.addColumn( COL_NUAGES, classOf[ AnyRef ])
      res
   }
   private val aggrTable            = {
      val res = vis.addAggregates( AGGR_PROC )
      res.addColumn( VisualItem.POLYGON, classOf[ Array[ Float ]])
//      res.addColumn( "id", classOf[ Int ]) // XXX not needed
      res
   }
//   val procG   = {
//      vis.addFocusGroup( GROUP_PROC )
//      vis.getGroup( GROUP_PROC )
//   }
   private var procMap              = Map.empty[ Proc, VisualProc ]
   private var edgeMap              = Map.empty[ ProcEdge, Edge ]
//   private var pendingProcs         = Set.empty[ Proc ]

//   private val topoListener : Model.Listener = {
//      case ProcsRemoved( procs @ _* )  => defer( topRemoveProcs( procs: _* ))
//      case ProcsAdded( procs @ _* )    => defer( topAddProcs( procs: _* ))
////      case EdgesRemoved( edges @ _* )     => defer( topRemoveEdges( edges: _* ))
////      case EdgesAdded( edges @ _* )       => defer( topAddEdges( edges: _* ))
//   }

   var transition: Double => Transition = (_) => Instant
   
   private object topoListener extends ProcWorld.Listener {
      def updated( u: ProcWorld.Update ) { defer( topoUpdate( u ))}
   }

   private object procListener extends Proc.Listener {
      def updated( u: Proc.Update ) { defer( procUpdate( u ))}
   }

//   private val procListener : Model.Listener = {
//      case PlayingChanged( proc, state )        => defer( topProcPlaying( proc, state ))
//      case ControlsChanged( controls @ _* )     => defer( topControlsChanged( controls: _* ))
//      case AudioBusesConnected( edges @ _* )    => defer( topAddEdges( edges: _* ))
//      case AudioBusesDisconnected( edges @ _* ) => defer( topRemoveEdges( edges: _* ))
//      case MappingsChanged( controls @ _* )     => defer( topMappingsChanged( controls: _* ))
//   }
   
   // ---- constructor ----
   {
//      vis.setValue( GROUP_NODES, null, VisualItem.SHAPE, new java.lang.Integer( Constants.SHAPE_ELLIPSE ))
//      vis.add( GROUP_GRAPH, g )
//      vis.addFocusGroup( GROUP_PAUSED, setPaused )

//      val nodeRenderer = new LabelRenderer( COL_LABEL )
      val procRenderer  = new NuagesProcRenderer( 50 )
//      val paramRenderer = new NuagesProcParamRenderer( 50 )
//      val nodeRenderer = new NuagesProcRenderer
//      nodeRenderer.setRenderType( AbstractShapeRenderer.RENDER_TYPE_FILL )
//      nodeRenderer.setHorizontalAlignment( Constants.LEFT )
//      nodeRenderer.setRoundedCorner( 8, 8 )
//      nodeRenderer.setVerticalPadding( 2 )
//      val edgeRenderer = new EdgeRenderer( Constants.EDGE_TYPE_CURVE )
      val edgeRenderer = new EdgeRenderer( Constants.EDGE_TYPE_LINE, Constants.EDGE_ARROW_FORWARD )
      val aggrRenderer = new PolygonRenderer( Constants.POLY_TYPE_CURVE )
      aggrRenderer.setCurveSlack( 0.15f )

      val rf = new DefaultRendererFactory( procRenderer )
//      val rf = new DefaultRendererFactory( new ShapeRenderer( 50 ))
//      rf.add( new InGroupPredicate( GROUP_PROC ), procRenderer )
      rf.add( new InGroupPredicate( GROUP_EDGES), edgeRenderer )
      rf.add( new InGroupPredicate( AGGR_PROC ), aggrRenderer )
//      val rf = new DefaultRendererFactory
//      rf.setDefaultRenderer( new ShapeRenderer( 20 ))
//      rf.add( "ingroup('aggregates')", aggrRenderer )
      vis.setRendererFactory( rf )

      // colors
      val actionNodeStroke = new ColorAction( GROUP_NODES, VisualItem.STROKECOLOR, ColorLib.rgb( 255, 255, 255 ))
      val actionNodeFill   = new ColorAction( GROUP_NODES, VisualItem.FILLCOLOR, ColorLib.rgb( 0, 0, 0 ))
//      actionNodeColor.add( new InGroupPredicate( GROUP_PAUSED ), ColorLib.rgb( 200, 0, 0 ))
      val actionTextColor = new ColorAction( GROUP_NODES, VisualItem.TEXTCOLOR, ColorLib.rgb( 255, 255, 255 ))

      val actionEdgeColor  = new ColorAction( GROUP_EDGES, VisualItem.STROKECOLOR, ColorLib.rgb( 255, 255, 255 ))
      val actionAggrFill   = new ColorAction( AGGR_PROC, VisualItem.FILLCOLOR, ColorLib.rgb( 127, 127, 127 ))
      val actionAggrStroke = new ColorAction( AGGR_PROC, VisualItem.STROKECOLOR, ColorLib.rgb( 255, 255, 255 ))
//      val fontAction       = new FontAction( GROUP_NODES, font )

      val lay = new ForceDirectedLayout( GROUP_GRAPH )

      // quick repaint
      val actionColor = new ActionList()
//      actionColor.add( fontAction )
      actionColor.add( actionTextColor )
      actionColor.add( actionNodeStroke )
      actionColor.add( actionNodeFill )
      actionColor.add( actionEdgeColor )
      actionColor.add( actionAggrFill )
      actionColor.add( actionAggrStroke )
//      actionColor.add( actionArrowColor )
      vis.putAction( ACTION_COLOR, actionColor )

//      vis.putAction( ACTION_EDGECOLOR, actionEdgeColor ) // para drag 'n drop

      val actionLayout = new ActionList( Activity.INFINITY, 50 )
      actionLayout.add( lay )
      actionLayout.add( new PrefuseAggregateLayout( AGGR_PROC ))
      actionLayout.add( new RepaintAction() )
      vis.putAction( ACTION_LAYOUT, actionLayout )
//      vis.runAfter( ACTION_COLOR, ACTION_LAYOUT )
      vis.alwaysRunAfter( ACTION_COLOR, ACTION_LAYOUT )

      // ------------------------------------------------

      // initialize the display
      display.setSize( 800, 600 )
//      display.setItemSorter( new TreeDepthItemSorter() )
//      display.addControlListener( new DragControl() )
//      display.addControlListener( new ZoomToFitControl() )
      display.addControlListener( new ZoomControl() )
      display.addControlListener( new WheelZoomControl() )
      display.addControlListener( new PanControl() )
      display.addControlListener( new DragControl( vis ))
      display.addControlListener( new ClickControl( this ))
////      val dragTgtHandle = vg.addNode().asInstanceOf[ NodeItem ]
//      val dummy = g.addNode()
//      val dragTgtHandle = vis.getVisualItem( GROUP_GRAPH, dummy ).asInstanceOf[ NodeItem ]
////      dragTgtHandle.setVisible( false )
//      dragTgtHandle.setSize( 0.0 )
//      dragTgtHandle.setFixed( true )
////      dragTgtHandle.setVisible( false )
////      display.addControlListener( new ConnectControl( vg, dragTgtHandle ))
//      display.addControlListener( new ConnectControl( g, dummy, dragTgtHandle, vis, GROUP_GRAPH ))
      display.addControlListener( new ConnectControl( vis ))
      display.setHighQuality( true )

      // ------------------------------------------------

//      nodeRenderer.setHorizontalAlignment( Constants.CENTER )
      edgeRenderer.setHorizontalAlignment1( Constants.CENTER )
      edgeRenderer.setHorizontalAlignment2( Constants.CENTER )
      edgeRenderer.setVerticalAlignment1( Constants.CENTER )
      edgeRenderer.setVerticalAlignment2( Constants.CENTER )

      display.setForeground( Color.WHITE )
      display.setBackground( Color.BLACK )

      setLayout( new BorderLayout() )
      add( display, BorderLayout.CENTER )

      vis.run( ACTION_COLOR )

      ProcTxn.atomic { implicit t => world.addListener( topoListener )}
   }

   // ---- ProcFactoryProvider ----
   var genFactory:    Option[ ProcFactory ] = None
   var filterFactory: Option[ ProcFactory ] = None
   var diffFactory:   Option[ ProcFactory ] = None
   private var locHintMap = Map.empty[ Proc, Point2D ]
   def setLocationHint( p: Proc, loc: Point2D ) {
//      println( "loc for " + p + " is " + loc )
      locHintMap += p -> loc
   }

   private def defer( code: => Unit ) {
      EventQueue.invokeLater( new Runnable { def run = code })
   }
   
   def dispose {
      ProcTxn.atomic { implicit t => world.removeListener( topoListener )}
      stopAnimation
   }

   private def stopAnimation {
      vis.cancel( ACTION_COLOR )
      vis.cancel( ACTION_LAYOUT )
   }

   private def startAnimation {
//      val pParent = pNode.get( PARENT ).asInstanceOf[ PNode ]
//      if( pParent != null ) {
//         val vi   = vis.getVisualItem( GROUP_TREE, pNode )
//         val vip  = vis.getVisualItem( GROUP_TREE, pParent )
//         if( vi != null && vip != null ) {
//            vi.setX( vip.getX )
//            vi.setY( vip.getY )
//         }
//      }
//      vis.run( ACTION_LAYOUT )
      vis.run( ACTION_COLOR )
   }

//   private def insertChild( pNode: PNode, pParent: PNode, info: OSCNodeInfo ) {
//      val pPred = if( info.predID == -1 ) {
//         pParent.set( HEAD, pNode )
//         null
//      } else {
//         map.get( info.predID ) orNull
//      }
//      if( pPred != null ) {
//         pPred.set( SUCC, pNode )
//         pNode.set( PRED, pPred )
//      }
//      val pSucc = if( info.succID == -1 ) {
//         pParent.set( TAIL, pNode )
//         null
//      } else {
//         map.get( info.succID ) orNull
//      }
//      if( pSucc != null ) {
//         pNode.set( SUCC, pSucc )
//         pSucc.set( PRED, pNode )
//      }
//      pNode.set( PARENT, pParent )
//   }

//   private def topAddProcs( procs: Proc* ) {
//      if( verbose ) println( "topAddProcs : " + procs )
////      pendingProcs ++= procs
//      // observer
//      vis.synchronized {
//         stopAnimation
//         procs.foreach( topAddProc( _ ))
//         startAnimation
//      }
//      ProcTxn.atomic { implicit t => procs.foreach( _.addListener( procListener ))}
//   }

   private def topAddProc( p: Proc ) {
      val pNode   = g.addNode()
      val vi      = vis.getVisualItem( GROUP_GRAPH, pNode )
      val locO    = locHintMap.get( p )
      locO.foreach( loc => {
         locHintMap -= p
         vi.setEndX( loc.getX() )
         vi.setEndY( loc.getY() )
      })
      val aggr = aggrTable.addItem().asInstanceOf[ AggregateItem ]
//println( "+ AGGR = " + aggr )
      aggr.addItem( vi )

      def createNode = {
         val pParamNode = g.addNode()
         val pParamEdge = g.addEdge( pNode, pParamNode )
         val vi         = vis.getVisualItem( GROUP_GRAPH, pParamNode )
         locO.foreach( loc => {
            vi.setEndX( loc.getX() )
            vi.setEndY( loc.getY() )
         })
         aggr.addItem( vi )
         (pParamNode, pParamEdge, vi)
      }

      lazy val vProc: VisualProc = {
         val vParams: Map[ String, VisualParam ] = p.params.collect({
            case pFloat: ProcParamFloat => {
               val (pParamNode, pParamEdge, vi) = createNode
               val pControl   = p.control( pFloat.name )
               val vControl   = VisualControl( pControl, pParamNode, pParamEdge )( vProc )
//               val mVal       = u.controls( pControl )
//               vControl.value = pFloat.spec.unmap( pFloat.spec.clip( mVal ))
               vi.set( COL_NUAGES, vControl )
               vControl.name -> vControl
            }
            case pParamBus: ProcParamAudioInput => {
               val (pParamNode, pParamEdge, vi) = createNode
               vi.set( VisualItem.SIZE, 0.33333f )
               val pBus = p.audioInput( pParamBus.name )
               val vBus = VisualAudioInput( panel, pBus, pParamNode, pParamEdge )
               vi.set( COL_NUAGES, vBus )
               vBus.name -> vBus
            }
            case pParamBus: ProcParamAudioOutput => {
               val (pParamNode, pParamEdge, vi) = createNode
               vi.set( VisualItem.SIZE, 0.33333f )
               val pBus = p.audioOutput( pParamBus.name )
               val vBus = VisualAudioOutput( panel, pBus, pParamNode, pParamEdge )
               vi.set( COL_NUAGES, vBus )
               vBus.name -> vBus
            }
         })( breakOut )
         val res = VisualProc( panel, p, pNode, aggr, vParams )
//         res.playing = u.playing == Some( true )
//         res.state =
         res
      }

      vi.set( COL_NUAGES, vProc )
// XXX this doesn't work. the vProc needs initial layout...
//      if( p.anatomy == ProcDiff ) vi.setFixed( true )
      procMap += p -> vProc

//      if( u.mappings.nonEmpty ) topMappingsChangedI( u.mappings )
//      if( u.audioBusesConnected.nonEmpty ) topAddEdgesI( u.audioBusesConnected )
   }

   private def topAddEdges( edges: Set[ ProcEdge ]) {
      if( verbose ) println( "topAddEdges : " + edges )
      vis.synchronized {
         stopAnimation
         topAddEdgesI( edges )
         startAnimation
      }
   }

   private def topAddEdgesI( edges: Set[ ProcEdge ]) {
      edges.foreach( e => {
         procMap.get( e.sourceVertex ).foreach( vProcSrc => {
            procMap.get( e.targetVertex ).foreach( vProcTgt => {
               val outName = e.out.name
               val inName  = e.in.name
               vProcSrc.params.get( outName ).map( _.pNode ).foreach( pSrc => {
                  vProcTgt.params.get( inName  ).map( _.pNode ).foreach( pTgt => {
                     val pEdge   = g.addEdge( pSrc, pTgt )
                     edgeMap    += e -> pEdge
                  })
               })
            })
         })
      })
   }

//   private def topMappingsChangedI( controls: Map[ ProcControl, ControlValue ]) {
//      val byProc = controls.groupBy( _._1.proc )
//      byProc.foreach( tup => {
//         val (proc, map) = tup
//         procMap.get( proc ).foreach( vProc => {
//            map.foreach( tup2 => {
//               val (ctrl, mo) = tup2
//               vProc.params.get( ctrl.name ) match {
//                  case Some( vControl: VisualControl ) => {
//                     vControl.mapping.foreach( vMap => {
//                        g.removeEdge( vMap.pEdge )
//                        vControl.mapping = None
//                     })
//                     vControl.mapping = mo.flatMap({
//                        case ma: ProcControlAMapping => {
//                           val aout = ma.edge.out
//                           procMap.get( aout.proc ).flatMap( vProc2 => {
//                              vProc2.params.get( aout.name ) match {
//                                 case Some( vBus: VisualAudioOutput ) => {
//                                    val pEdge = g.addEdge( vBus.pNode, vControl.pNode )
//                                    Some( VisualMapping( ma, pEdge ))
//                                 }
//                                 case _ => None
//                              }
//                           })
//                        }
//                        case _ => None
//                     })
//                  }
//                  case _ =>
//               }
//            })
//            // damageReport XXX
//         })
//      })
//   }

//   private def topMappingsChanged( controls: Map[ ProcControl, ControlValue ]) {
//      if( verbose ) println( "topMappingsChanged : " + controls )
//      vis.synchronized {
//         stopAnimation
//         topMappingsChangedI( controls )
//         startAnimation
//      }
//   }

//   case class Update( playing: Option[ Boolean ],
//                      controls: IMap[ ProcControl, Float ],
//                      mappings: IMap[ ProcControl, Option[ ProcControlMapping ]],
//                      audioBusesConnected: ISet[ ProcEdge ],
//                      audioBusesDisconnected: ISet[ ProcEdge ])
   private def procUpdate( u: Proc.Update ) {
      if( verbose ) println( "" + new java.util.Date() + " procUpdate: " + u )
      val p = u.proc
      procMap.get( p ).foreach( vProc => {
//         u.playing.foreach( state => topProcPlaying( p, state ))
//         if( u.state != Proc.STATE_UNDEFINED ) topProcState( vProc, u.state )
         if( u.state.valid ) {
            if( verbose ) println( "procUpdate : u.state = " + u.state )
            vProc.state = u.state
         }
         if( u.controls.nonEmpty )               topControlsChanged( u.controls )
//         if( u.mappings.nonEmpty )               topMappingsChanged( u.mappings )
         if( u.audioBusesConnected.nonEmpty )    topAddEdges( u.audioBusesConnected )
         if( u.audioBusesDisconnected.nonEmpty ) topRemoveEdges( u.audioBusesDisconnected )
//         if( !vProc.valid ) {
//            vProc.valid = true
//            vProc.params.foreach( _._2.valid = true )
//         }
      })
   }

//   private def topoUpdate( u: ProcWorld.Update ) {
//      if( u.procsRemoved.nonEmpty ) topRemoveProcs( u.procsRemoved.toSeq: _* )
//      if( u.procsAdded.nonEmpty )   topAddProcs(    u.procsAdded.toSeq:   _* )
//   }

//   private def topRemoveProcs( procs: Proc* ) {
//      if( verbose ) println( "topRemoveProcs : " + procs )
//      vis.synchronized {
//         stopAnimation
//         ProcTxn.atomic { implicit t => procs.foreach( _.removeListener( procListener ))}
//         procs.foreach( p => {
//            procMap.get( p ).map( topRemoveProc( _ )) // .getOrElse( pendingProcs -= p )
//         })
//         startAnimation
//      }
//   }

   private def topoUpdate( u: ProcWorld.Update ) {
println( "" + new java.util.Date() + " topoUpdate : " + u )
      vis.synchronized {
         ProcTxn.atomic { implicit t =>
            u.procsRemoved foreach { p =>
               p.removeListener( procListener )
               procMap.get( p ).map( topRemoveProc( _ ))
            }
            u.procsAdded foreach { p =>
               topAddProc( p )
               p.addListener( procListener )
            }
         }
         startAnimation
      }
   }

//   private def tryDeleteAggr( vProc: VisualProc ) {
//      var retries = 3
//      lazy val tim: javax.swing.Timer = new javax.swing.Timer( 2000, new ActionListener {
//         def actionPerformed( e: ActionEvent ) = try {
////            println( "RETRYING AGGR..." )
//            vis.synchronized {
//               println( "RETRY - AGGR = " + vProc.aggr )
//               aggrTable.removeTuple( vProc.aggr )
//            }
//            tim.stop
//         }
//         catch { case e => {
//            Predef.print( "CAUGHT : " ); e.printStackTrace()
////            println( "CAUGHT: " + e.getClass().getName() )
//            if( retries == 0 ) tim.stop else retries -= 1
//         }}
//      })
//      tim.start
//   }

   private def topRemoveProc( vProc: VisualProc ) {
//      val vi = vis.getVisualItem( GROUP_GRAPH, vProc.pNode )
//      procG.removeTuple( vi )
//      try {
//         println( "- AGGR = " + vProc.aggr )
         aggrTable.removeTuple( vProc.aggr )
//      }
//      catch { case e => {  // FUCKING PREFUSE BUG?!
//         Predef.print( "CAUGHT : " ); e.printStackTrace()
////         println( "CAUGHT: " + e.getClass().getName() )
//         tryDeleteAggr( vProc )
//      }}
      g.removeNode( vProc.pNode )
//      aggrTable.removeTuple( vProc.aggr ) // XXX OK???
      vProc.params.values.foreach( vParam => {
// WE MUST NOT REMOVE THE EDGES, AS THE REMOVAL OF
// VPROC.PNODE INCLUDES ALL THE EDGES!
//         g.removeEdge( vParam.pEdge )
         g.removeNode( vParam.pNode )
      })
//      aggrTable.removeTuple( vProc.aggr ) // XXX OK???
      procMap -= vProc.proc
   }

   private def topRemoveEdges( edges: Set[ ProcEdge ]) {
      if( verbose ) println( "topRemoveEdges : " + edges )
      vis.synchronized {
         stopAnimation
         edges.foreach( e => {
            edgeMap.get( e ).foreach( pEdge => {
               try {
                  g.removeEdge( pEdge )
               }
               catch {
                  case ex => println( "" + new java.util.Date() + " CAUGHT " + e + " : " + ex.getClass().getName )
               }
               edgeMap -= e
            })
         })
         startAnimation
      }
   }
//
//   private def topProcState( vProc: VisualProc, state: Int ) {
////      procMap.get( p ).foreach( vProc => {
//println( "STATE = " + state )
//         vProc.state = state
//         // damageReport XXX
////      })
//   }

   private def topRemoveControlMap( vControl: VisualControl, vMap: VisualMapping ) {
      g.removeEdge( vMap.pEdge )
      vControl.mapping = None
   }

   private def topAddControlMap( vControl: VisualControl, m: ControlBusMapping ) {
      vControl.mapping = m match {
         case ma: ControlABusMapping => {
            val aout = ma.out // edge.out
            procMap.get( aout.proc ).flatMap( vProc2 => {
               vProc2.params.get( aout.name ) match {
                  case Some( vBus: VisualAudioOutput ) => {
                     val pEdge = g.addEdge( vBus.pNode, vControl.pNode )
                     Some( VisualMapping( ma, pEdge ))
                  }
                  case _ => None
               }
            })
         }
//         case _ =>
      }
   }

   private def topControlsChanged( controls: Map[ ProcControl, ControlValue ]) {
      val byProc = controls.groupBy( _._1.proc )
      byProc.foreach( tup => {
         val (proc, map) = tup
         procMap.get( proc ).foreach( vProc => {
            map.foreach( tup2 => {
               val (ctrl, cv) = tup2
               vProc.params.get( ctrl.name ) match {
                  case Some( vControl: VisualControl ) => {
                     vControl.value = cv
//                     vControl.value = {
//                        val spec = ctrl.spec
//                        spec.unmap( spec.clip( cv.currentApprox ))
//                     }
                     vControl.gliding = if( vControl.mapping.isDefined || cv.mapping.isDefined ) {
                        vControl.mapping match {
                           case Some( vMap ) => if( cv.mapping != Some( vMap.mapping )) {
                              topRemoveControlMap( vControl, vMap )
                              cv.mapping match {
                                 case Some( bm: ControlBusMapping ) => { topAddControlMap( vControl, bm ); false }
                                 case Some( g: ControlGliding ) => true
                                 case _ => false
                              }
                           } else false
                           case None => cv.mapping match {
                              case Some( bm: ControlBusMapping ) => { topAddControlMap( vControl, bm ); false }
                              case Some( g: ControlGliding ) => true
                              case _ => false
                           }
                        }
                     } else false
                  }
                  case _ =>
               }
            })
            // damageReport XXX
         })
      })
   }
}