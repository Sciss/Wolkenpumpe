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
import de.sciss.synth

/**
 *    @version 0.11, 21-Jul-10
 */
object NuagesPanel {
   var verbose = false

   val GROUP_GRAPH            = "graph"
   private val GROUP_NODES    = "graph.nodes"
   private val GROUP_EDGES    = "graph.edges"
   val COL_NUAGES             = "nuages"
}

class NuagesPanel( server: Server, meters: Boolean ) extends JPanel
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
   private val LAYOUT_TIME          = 50
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
   private var meterMap             = Map.empty[ Proc, VisualProc ]
//   private var pendingProcs         = Set.empty[ Proc ]

//   private val topoListener : Model.Listener = {
//      case ProcsRemoved( procs @ _* )  => defer( topRemoveProcs( procs: _* ))
//      case ProcsAdded( procs @ _* )    => defer( topAddProcs( procs: _* ))
////      case EdgesRemoved( edges @ _* )     => defer( topRemoveEdges( edges: _* ))
////      case EdgesAdded( edges @ _* )       => defer( topAddEdges( edges: _* ))
//   }

   var transition: Double => Transition = (_) => Instant

   var meterFactory : ProcFactory = null
   
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

      val actionLayout = new ActionList( Activity.INFINITY, LAYOUT_TIME )
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

      ProcTxn.atomic { implicit t =>
         import DSL._
         if( meters ) meterFactory = diff( "$meter" ) {
            import synth._
            import ugen._

//            val pMeter = pControl( "midx", ParamSpec( 0, 0xFFFF ), 0 )

            graph { sig =>
               val meterTr    = Impulse.kr( 1000.0 / LAYOUT_TIME ) // "$m_tr".tr
//               val meterIdx   = pMeter.kr // "$m_idx".kr
//               val trigA      = Trig1.ar( meterTr, SampleDur.ir )
               val peak       = Peak.kr( sig, meterTr /* trigA */ ).outputs
               val peakM      = peak.tail.foldLeft[ GE ]( peak.head )( _ max _ ) \ 0
//               val rms        = (Mix( Lag.ar( sig.squared, 0.1 )) / sig.numOutputs) \ 0
               val me         = Proc.local
               // warning: currently a bug in SendReply? if values are audio-rate,
               // trigger needs to be audio-rate, too
               meterTr.react( peakM /* :: rms :: Nil */ ) { vals =>
                  defer( meterMap.get( me ).foreach { visOut =>
                     visOut.meterUpdate( vals( 0 ).toFloat /* , vals( 1 ).toFloat */)
                  })
               }
//               trigA.react( peakM :: rms :: Nil ) { x => println( "JO" )}
               0.0
            }
         }
         world.addListener( topoListener )
      }
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
      vis.run( ACTION_COLOR )
   }

   private def topAddProc( p: Proc )( implicit t: ProcTxn ) {
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

      var meterBusOption : Option[ ProcAudioOutput ] = None

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
//               if( meters && (vBus.name == "in") && meterBusOption.isEmpty ) meterBusOption = Some( pBus )
               vBus.name -> vBus
            }
            case pParamBus: ProcParamAudioOutput => {
               val (pParamNode, pParamEdge, vi) = createNode
               vi.set( VisualItem.SIZE, 0.33333f )
               val pBus = p.audioOutput( pParamBus.name )
               val vBus = VisualAudioOutput( panel, pBus, pParamNode, pParamEdge )
               vi.set( COL_NUAGES, vBus )
               if( meters && vBus.name == "out" ) meterBusOption = Some( pBus )
               vBus.name -> vBus
            }
         })( breakOut )

         val pMeter = meterBusOption.map { bus =>
            import DSL._
            val res = meterFactory.make
//                  meterMap += res -> vBus
            bus ~> res
            if( p.isPlaying ) res.play
            res
         }

         val res = VisualProc( panel, p, pNode, aggr, vParams, pMeter )
         pMeter.foreach { pm => meterMap += pm -> res }
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

   private def procUpdate( u: Proc.Update ) {
      if( verbose ) println( "" + new java.util.Date() + " procUpdate: " + u )
      val p = u.proc
      procMap.get( p ).foreach( vProc => {
//         u.playing.foreach( state => topProcPlaying( p, state ))
//         if( u.state != Proc.STATE_UNDEFINED ) topProcState( vProc, u.state )
         if( u.state.valid ) {
            if( verbose ) println( "procUpdate : u.state = " + u.state )
            val wasPlaying = vProc.state.playing
            vProc.state = u.state
            if( !wasPlaying && u.state.playing ) {
//               val pms = vProc.params.collect { case (_, VisualAudioOutput( _, _, _, _, Some( pMeter ))) => pMeter }
               vProc.meter.foreach { pm => ProcTxn.atomic { implicit t => pm.play }}
            }
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

   private def topoUpdate( u: ProcWorld.Update ) {
      if( verbose ) println( "" + new java.util.Date() + " topoUpdate : " + u )
      vis.synchronized {
         ProcTxn.atomic { implicit t =>
            u.procsRemoved.filterNot( _.name.startsWith( "$" )) foreach { p =>
               p.removeListener( procListener )
               procMap.get( p ).map( topRemoveProc( _ ))
            }
            u.procsAdded.filterNot( _.name.startsWith( "$" )) foreach { p =>
               topAddProc( p )
               p.addListener( procListener )
            }
         }
         startAnimation
      }
   }

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