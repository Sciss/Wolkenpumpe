/*
 *  PanelImpl.scala
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
package impl

import java.awt.{Dimension, Rectangle, LayoutManager, Point, Color}
import java.awt.geom.Point2D
import javax.swing.event.{AncestorEvent, AncestorListener}
import javax.swing.JPanel

import de.sciss.lucre.bitemp.{SpanLike => SpanLikeEx}
import de.sciss.lucre.stm
import de.sciss.lucre.stm.Disposable
import de.sciss.lucre.swing.{ListView, deferTx, requireEDT}
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.synth.Sys
import de.sciss.model.Change
import de.sciss.span.{SpanLike, Span}
import de.sciss.synth.ugen.Out
import de.sciss.synth.{proc, GE, AudioBus}
import de.sciss.synth.proc.{Action, WorkspaceHandle, Scan, ExprImplicits, AuralSystem, Transport, Timeline, Proc, Folder, Obj}
import prefuse.action.{RepaintAction, ActionList}
import prefuse.action.assignment.ColorAction
import prefuse.action.layout.graph.ForceDirectedLayout
import prefuse.activity.Activity
import prefuse.controls.{PanControl, WheelZoomControl, ZoomControl}
import prefuse.data.Graph
import prefuse.render.{DefaultRendererFactory, PolygonRenderer, EdgeRenderer}
import prefuse.util.ColorLib
import prefuse.visual.{AggregateItem, AggregateTable, VisualGraph, VisualItem}
import prefuse.visual.expression.InGroupPredicate
import prefuse.{Constants, Display, Visualization}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.{Ref, TxnLocal}
import scala.swing.{Button, Container, Orientation, SequentialContainer, BoxPanel, Panel, Swing, Component}

object PanelImpl {
  var DEBUG = true

  def apply[S <: Sys[S]](nuages: Nuages[S], config: Nuages.Config, aural: AuralSystem)
                        (implicit tx: S#Tx, cursor: stm.Cursor[S], workspace: WorkspaceHandle[S]): NuagesPanel[S] = {
    val nuagesH   = tx.newHandle(nuages)
    val listGen   = mkListView(nuages.generators)
    val listFlt   = mkListView(nuages.filters   )
    val listCol   = mkListView(nuages.collectors)
    val nodeMap   = tx.newInMemoryIDMap[VisualObj[S]]
    val scanMap   = tx.newInMemoryIDMap[ScanInfo [S]]
    val transport = Transport[S](aural)
    val timelineObj = nuages.timeline
    transport.addObject(timelineObj)

    new Impl[S](nuagesH, nodeMap, scanMap, config, transport, listGen = listGen, listFlt = listFlt, listCol = listCol)
      .init(timelineObj)
  }

  private final val GROUP_NODES = "graph.nodes"
  private final val GROUP_EDGES = "graph.edges"

  private final val AGGR_PROC = "aggr"
  private final val ACTION_LAYOUT = "layout"
  //   private val ACTION_LAYOUT_ANIM   = "layout-anim"
  private final val ACTION_COLOR = "color"
  //   private val ACTION_COLOR_ANIM    = "layout-anim"
  private final val LAYOUT_TIME = 50
  //   private val FADE_TIME            = 333

  private def mkListView[S <: Sys[S]](folder: Folder[S])(implicit tx: S#Tx, cursor: stm.Cursor[S]) = {
    import proc.Implicits._
    val h = ListView.Handler[S, Obj[S], Obj.Update[S]] { implicit tx => obj => obj.name } (_ => (_, _) => None)
    implicit val ser = de.sciss.lucre.expr.List.serializer[S, Obj[S], Obj.Update[S]]
    val res = ListView[S, Obj[S], Obj.Update[S], String](folder, h)
    deferTx {
      val c = res.view
      // c.peer.setUI(new BasicListUI)
      c.background = Color.black
      c.foreground = Color.white
      c.selectIndices(0)
    }
    res
  }

  private final class VisualLink[S <: Sys[S]](val source: VisualObj[S], val sourceKey: String,
                                              val sink  : VisualObj[S], val sinkKey  : String)

  private final case class ScanInfo[S <: Sys[S]](id: S#ID, key: String)

  private final class Impl[S <: Sys[S]](nuagesH: stm.Source[S#Tx, Nuages[S]],
                                        nodeMap: stm.IdentifierMap[S#ID, S#Tx, VisualObj[S]],
                                        scanMap: stm.IdentifierMap[S#ID, S#Tx, ScanInfo[S]],
                                        val config: Nuages.Config,
                                        val transport: Transport[S],
                                        listGen: ListView[S, Obj[S], Obj.Update[S]],
                                        listFlt: ListView[S, Obj[S], Obj.Update[S]],
                                        listCol: ListView[S, Obj[S], Obj.Update[S]])
                                       (implicit cursor: stm.Cursor[S], workspace: WorkspaceHandle[S])
    extends NuagesPanel[S] with ComponentHolder[Component] {
    panel =>

    import NuagesPanel._
    import cursor.{step => atomic}

    private var _vis: Visualization = _
    private var _dsp: Display = _

    private var soloFactory: Option[stm.Source[S#Tx, Proc.Obj[S]]] = None

    private var g: Graph = _
    private var vg: VisualGraph = _

    private var aggrTable: AggregateTable = _

    //  /* private */ var procMap   = Map.empty[Proc, VisualProc]
    //  /* private */ var edgeMap   = Map.empty[ProcEdge, Edge]
    //  /* private */ var meterMap  = Map.empty[Proc, VisualProc]

    //  var transition: Double => Transition = (_) => Instant

    private var meterFactory : Option[stm.Source[S#Tx, Proc.Obj[S]]] = None
    //  private var masterFactory: Option[ProcFactory]  = None
    //  private var masterProc   : Option[Proc]         = None

    private var masterBusVar : Option[AudioBus]     = None

/*
      private object topoListener extends ProcWorld.Listener {
        def updated(u: ProcWorld.Update): Unit = topoUpdate(u)
      }

      private object procListener extends Proc.Listener {
        def updated(u: Proc.Update): Unit =
          defer(procUpdate(u))
      }

    ---- ProcFactoryProvider ----
      val factoryManager      = new ProcFactoryViewManager(config)
      var genFactory          = Option.empty[ProcFactory]
      var filterFactory       = Option.empty[ProcFactory]
      var diffFactory         = Option.empty[ProcFactory]
      var collector           = Option.empty[Proc]
*/

    private val locHintMap = TxnLocal(Map.empty[Obj[S], Point2D])

    private var overlay = Option.empty[Component]

    private var observer: Disposable[S#Tx] = _

    def display: Display = _dsp
    def visualization: Visualization = _vis

    def masterBus: Option[AudioBus] = masterBusVar

    def setLocationHint(p: Obj[S], loc: Point2D)(implicit tx: S#Tx): Unit =
      locHintMap.transform(_ + (p -> loc))(tx.peer)

    def nuages(implicit tx: S#Tx): Nuages[S] = nuagesH()

    def dispose()(implicit tx: S#Tx): Unit = {
      deferTx(stopAnimation())
      observer .dispose()
      transport.dispose()
      nodeMap  .dispose()
      scanMap  .dispose()
    }

    def init(timeline: Timeline.Obj[S])(implicit tx: S#Tx): this.type = {
      deferTx(guiInit())
      observer = timeline.elem.peer.changed.react { implicit tx => upd =>
        upd.changes.foreach {
          case Timeline.Added(span, timed) =>
            if (span.contains(transport.position)) addNode(span, timed)
            // XXX TODO - update scheduler

          case Timeline.Removed(span, timed) =>
            if (span.contains(transport.position)) removeNode(span, timed)
          // XXX TODO - update scheduler

          case Timeline.Element(timed, Obj.UpdateT(obj, changes)) =>
            nodeMap.get(timed.id).foreach { visObj =>
              changes.foreach {
                case Obj.AttrChange(key, _, attrChanges) if visObj.params.contains(key) =>
                  attrChanges.foreach {
                    case Obj.ElemChange(Change(_, now: Double)) =>
                      // println(s"NOW $now")
                      deferTx {
                        visObj.params.get(key).foreach { visCtl =>
                          visCtl.value = now
                          val visItem = _vis.getVisualItem(GROUP_GRAPH, visCtl.pNode)
                          _vis.damageReport(visItem, visItem.getBounds)
                        }
                      }

                    // XXX TODO - ParamSpec changes

                    case other => if (DEBUG) println(s"OBSERVED: Timeline.Element - AttrChange($key, $other)")
                  }

                case Obj.ElemChange(pUpd: Proc.Update[S]) =>
                  pUpd.changes.foreach {
                    case Proc.ScanChange(key, scan, scanChanges) =>
                      def withScans(sink: Scan.Link[S])(fun: (VisualScan[S], VisualScan[S]) => Unit): Unit =
                        for {
                          sinkInfo <- scanMap.get(sink.id)
                          sinkVis  <- nodeMap.get(sinkInfo.id)
                        } deferTx {
                          for {
                            sourceVisScan <- visObj .scans.get(key)
                            sinkVisScan   <- sinkVis.scans.get(sinkInfo.key)
                          } visDo {
                            fun(sourceVisScan, sinkVisScan)
                          }
                        }

                      scanChanges.foreach {
                        case Scan.SinkAdded  (sink) => withScans(sink)(addEdgeGUI   )
                        case Scan.SinkRemoved(sink) => withScans(sink)(removeEdgeGUI)
                        case _ =>
                      }

                    case other => if (DEBUG) println(s"OBSERVED: Timeline.Element - ProcChange($other)")
                  }

                case other => if (DEBUG) println(s"OBSERVED: Timeline.Element - $other")
              }
            }

          case other => if (DEBUG) println(s"OBSERVED: $other")
        }
      }
      this
    }

    // ---- constructor ----
    private def guiInit(): Unit = {
      _vis  = new Visualization
      _dsp = new Display(_vis)

      g     = new Graph
      vg    = _vis.addGraph(GROUP_GRAPH, g)
      vg.addColumn(COL_NUAGES, classOf[AnyRef])
      aggrTable = _vis.addAggregates(AGGR_PROC)
      aggrTable .addColumn(VisualItem.POLYGON, classOf[Array[Float]])

      val procRenderer = new NuagesProcRenderer(50)
      val edgeRenderer = new EdgeRenderer(Constants.EDGE_TYPE_LINE, Constants.EDGE_ARROW_FORWARD)
      val aggrRenderer = new PolygonRenderer(Constants.POLY_TYPE_CURVE)
      aggrRenderer.setCurveSlack(0.15f)

      val rf = new DefaultRendererFactory(procRenderer)
      rf.add(new InGroupPredicate(GROUP_EDGES), edgeRenderer)
      rf.add(new InGroupPredicate(AGGR_PROC  ), aggrRenderer)
      _vis.setRendererFactory(rf)

      // colors
      val actionNodeStroke  = new ColorAction(GROUP_NODES, VisualItem.STROKECOLOR, ColorLib.rgb(255, 255, 255))
      val actionNodeFill    = new ColorAction(GROUP_NODES, VisualItem.FILLCOLOR  , ColorLib.rgb(0, 0, 0))
      val actionTextColor   = new ColorAction(GROUP_NODES, VisualItem.TEXTCOLOR  , ColorLib.rgb(255, 255, 255))

      val actionEdgeColor   = new ColorAction(GROUP_EDGES, VisualItem.STROKECOLOR, ColorLib.rgb(255, 255, 255))
      val actionAggrFill    = new ColorAction(AGGR_PROC  , VisualItem.FILLCOLOR  , ColorLib.rgb(80, 80, 80))
      val actionAggrStroke  = new ColorAction(AGGR_PROC  , VisualItem.STROKECOLOR, ColorLib.rgb(255, 255, 255))

      val lay = new ForceDirectedLayout(GROUP_GRAPH)

      // quick repaint
      val actionColor = new ActionList()
      actionColor.add(actionTextColor)
      actionColor.add(actionNodeStroke)
      actionColor.add(actionNodeFill)
      actionColor.add(actionEdgeColor)
      actionColor.add(actionAggrFill)
      actionColor.add(actionAggrStroke)
      _vis.putAction(ACTION_COLOR, actionColor)

      val actionLayout = new ActionList(Activity.INFINITY, LAYOUT_TIME)
      actionLayout.add(lay)
      actionLayout.add(new PrefuseAggregateLayout(AGGR_PROC))
      actionLayout.add(new RepaintAction())
      _vis.putAction(ACTION_LAYOUT, actionLayout)
      _vis.alwaysRunAfter(ACTION_COLOR, ACTION_LAYOUT)

      // ------------------------------------------------

      // initialize the display
      _dsp.setSize(800, 600)
      _dsp.addControlListener(new ZoomControl())
      _dsp.addControlListener(new WheelZoomControl())
      _dsp.addControlListener(new PanControl())
      _dsp.addControlListener(new DragControl(_vis))
      _dsp.addControlListener(new ClickControl(this))
      _dsp.addControlListener(new ConnectControl(_vis))
      _dsp.setHighQuality(true)

      // ------------------------------------------------

      edgeRenderer.setHorizontalAlignment1(Constants.CENTER)
      edgeRenderer.setHorizontalAlignment2(Constants.CENTER)
      edgeRenderer.setVerticalAlignment1(Constants.CENTER)
      edgeRenderer.setVerticalAlignment2(Constants.CENTER)

      _dsp.setForeground(Color.WHITE)
      _dsp.setBackground(Color.BLACK)

      //      setLayout( new BorderLayout() )
      //      add( display, BorderLayout.CENTER )
      val p = new JPanel
      p.setLayout(new Layout(_dsp))
      p.add(_dsp)

      _vis.run(ACTION_COLOR)

      component = Component.wrap(p)
    }

/*
            // import DSL._
            import synth._
            import ugen._

            def meterGraph(sig: In): Unit = {
              val meterTr = Impulse.kr(1000.0 / LAYOUT_TIME)
              val peak = Peak.kr(sig, meterTr) // .outputs
              //            val peakM      = peak.tail.foldLeft[ GE ]( peak.head )( _ max _ ) \ 0
              val peakM = Reduce.max(peak)
              val me = Proc.local
              // warning: currently a bug in SendReply? if values are audio-rate,
              // trigger needs to be audio-rate, too
              meterTr.react(peakM /* :: rms :: Nil */) { vals =>
                defer(meterMap.get(me).foreach { visOut =>
                  visOut.meterUpdate(vals(0).toFloat)
                })
              }
            }

            if (config.meters) meterFactory = Some(diff("$meter") {
              graph { sig: In =>
                meterGraph(sig)
                0.0
              }
            })

            if (config.collector) {
              if (verbose) Console.print("Creating collector...")
              val p = (filter("_+")(graph((x: In) => x))).make
              collector = Some(p)
              if (verbose) println(" ok")
            }

            config.masterChannels.foreach { chans =>
              if (verbose) Console.print("Creating master...")
              val name = if (config.collector) "_master" else "$master"
              val pMaster = (diff(name) {
                val pAmp = pControl("amp", masterAmpSpec._1, masterAmpSpec._2)
                /* val pIn = */ pAudioIn("in", None)
                graph { (sig: In) => efficientOuts(Limiter.ar(sig * pAmp.kr, (-0.2).dbamp), chans); 0.0}
              }).make
              pMaster.control("amp").v = masterAmpSpec._2
              // XXX bus should be freed in panel disposal

              val b = Bus.audio(config.server, chans.size)
              collector match {
                case Some(pColl) =>
                  val b2 = Bus.audio(config.server, chans.size)
                  pColl.audioInput("in").bus = Some(RichBus.wrap(b2))
                  pColl ~> pMaster
                  pColl.play
                case None =>
              }
              pMaster.audioInput("in").bus = Some(RichBus.wrap(b))

              val g = RichGroup(Group(config.server))
              g.play(RichGroup.default(config.server), addToTail)
              pMaster.group = g
              pMaster.play
              masterProc = Some(pMaster)
              masterBusVar = Some(b)

              masterFactory = Some(diff("$diff") {
                graph { (sig: In) =>
                  //                  require( sig.numOutputs == b.numChannels )
                  if (sig.numChannels != b.numChannels) println("Warning - masterFactory. sig has " + sig.numChannels + " outputs, but bus has " + b.numChannels + " channels ")
                  if (config.meters) meterGraph(sig)
                  Out.ar(b.index, sig)
                }
              })

              factoryManager.startListening
              if (verbose) println(" ok")
            }

            soloFactory = config.soloChannels.map { chans => diff("$solo") {
              val pAmp = pControl("amp", soloAmpSpec._1, soloAmpSpec._2)

              graph { sig: In =>
                //               val numIn   = sig.numOutputs
                val numOut = chans.size
                //               val sigOut  = Array.fill[ GE ]( numOut )( 0.0f )
                //               val sca     = (numOut - 1).toFloat / (numIn - 1)

                val sigOut = SplayAz.ar(numOut, sig) // , spread, center, level, width, orient

                //               sig.outputs.zipWithIndex.foreach { tup =>
                //                  val (sigIn, inCh) = tup
                //                  val outCh         = inCh * sca
                //                  val fr            = outCh % 1f
                //                  val outChI        = outCh.toInt
                //                  if( fr == 0f ) {
                //                     sigOut( outChI ) += sigIn
                //                  } else {
                //                     sigOut( outChI )     += sigIn * (1 - fr).sqrt
                //                     sigOut( outChI + 1 ) += sigIn * fr.sqrt
                //                  }
                //               }
                efficientOuts(sigOut /*.toSeq */ * pAmp.kr, chans)
                0.0
              }
            }
            }

            world.addListener(topoListener)
          }
*/

    private def efficientOuts(sig: GE, chans: Vec[Int]): Unit = {
      //      require( sig.numOutputs == chans.size, sig.numOutputs.toString + " =! " + chans.size )
      //if( sig.numOutputs != chans.size ) println( "WARNING: efficientOuts. sig has " + sig.numOutputs.toString + " outs, while chans has = " + chans.size )
      if (chans.isEmpty) return

      require(chans == Vec.tabulate(chans.size)(i => i + chans(0)))
      Out.ar(chans(0), sig)

/*
            import synth._
            import ugen._

            def funky( seq: Vec[ (Int, UGenIn) ]) {
               seq.headOption.foreach { tup =>
                  val (idx, e1)  = tup
                  var cnt = -1
                  val (ja, nein) = seq.span { tup =>
                     val (idx2, _) = tup
                     cnt += 1
                     idx2 == idx + cnt
                  }
                  Out.ar( idx, ja.map( _._2 ) : GE )
                  funky( nein )
               }
            }

            funky( chans.zip( sig.outputs ).sortBy( _._1 ))
*/
    }

    def showOverlayPanel(p: Component, pt: Point): Boolean = {
      if (overlay.isDefined) return false
      val pp = p.peer
      val c = component
      val x = math.max(0, math.min(pt.getX.toInt , c.peer.getWidth  - pp.getWidth ))
      val y = math.min(math.max(0, pt.getY.toInt), c.peer.getHeight - pp.getHeight) // make sure bottom is visible
      pp.setLocation(x, y)
      //println( "aqui " + p.getX + ", " + p.getY + ", " + p.getWidth + ", " + p.getHeight )
      c.peer.add(pp, 0)
      c.revalidate()
      c.repaint()
      pp.addAncestorListener(new AncestorListener {
        def ancestorAdded(e: AncestorEvent) = ()

        def ancestorRemoved(e: AncestorEvent): Unit = {
          pp.removeAncestorListener(this)
          if (Some(p) == overlay) overlay = None
        }

        def ancestorMoved(e: AncestorEvent) = ()
      })
      overlay = Some(p)
      true
    }

    def isOverlayShowing: Boolean = overlay.isDefined

    def dispose(): Unit = {
      stopAnimation()

      if (config.collector) println("WARNING! NuagesPanel.dispose -- doesn't handle the collector yet")

      atomic { implicit t =>
        //      factoryManager.stopListening
        //      world.removeListener(topoListener)
        //      masterProc.foreach { pMaster =>
        //        pMaster.dispose
        //        pMaster.group.free(true)
        //      }
        masterBus.foreach(_.free())
      }
    }

    private def stopAnimation(): Unit = {
      _vis.cancel(ACTION_COLOR)
      _vis.cancel(ACTION_LAYOUT)
    }

    private def startAnimation(): Unit =
      _vis.run(ACTION_COLOR)

    private def visDo[A](code: => A): A =
      _vis.synchronized {
        stopAnimation()
        try {
          code
        } finally {
          startAnimation()
        }
      }

    def addNode(span: SpanLike, timed: Timeline.Timed[S])(implicit tx: S#Tx): Unit = {
      val id   = timed.id
      val obj  = timed.value
      // val n = proc.attr.expr[String](ObjKeys.attrName).fold("<unnamed>")(_.value)
      // val n = proc.name.value
      //            val par  = proc.par.entriesAt( time )
      // val par = Map.empty[String, Double]
      // val vp    = new VisualProc[S](n, par, cursor.position, tx.newHandle(proc))
      val vp = VisualObj[S](this, timed.span, obj, pMeter = None,
        meter = meterFactory.isDefined, solo = soloFactory.isDefined)
      // val span = timed.span.value
      //      map.get(id) match {
      //        case Some(vpm) =>
      //          map.remove(id)
      //          map.put(id, vpm + (span -> (vp :: vpm.getOrElse(span, Nil))))
      //        case _ =>
      //          map.put(id, Map(span -> (vp :: Nil)))
      //      }
      nodeMap.put(id, vp)
      val locO = locHintMap.getAndTransform(_ - obj)(tx.peer).get(obj)

      val links: List[VisualLink[S]] = obj match {
        case Proc.Obj(objT) =>
          val scans = objT.elem.peer.scans
          scans.iterator.flatMap { case (key, scan) =>
            val info = new ScanInfo(id, key)
            addScan(vp, scan, info)
          } .toList
        case _ => Nil
      }

      deferTx(visDo(addNodeGUI(vp, links, locO)))
    }

    def removeNode(span: SpanLike, timed: Timeline.Timed[S])(implicit tx: S#Tx): Unit = {
      val id   = timed.id
      val obj  = timed.value
      nodeMap.get(id).foreach { vp =>
        nodeMap.remove(id)
        obj match {
          case Proc.Obj(objT) =>
            val scans = objT.elem.peer.scans
            scans.iterator.foreach { case (_, scan) =>
              scanMap.remove(scan.id)
            }
          case _ =>
        }

        deferTx(visDo(removeNodeGUI(vp)))

        disposeObj(obj)
      }
    }

    private def addScan(vi: VisualObj[S], scan: Scan[S], info: ScanInfo[S])(implicit tx: S#Tx): List[VisualLink[S]] = {
      scanMap.put(scan.id, info)  // stupid look-up
      var res = List.empty[VisualLink[S]]
      scan.sources.foreach {
        case Scan.Link.Scan(source) =>
          for {
            ScanInfo(sourceTimedID, sourceKey) <- scanMap.get(source.id)
            sourceVis <- nodeMap.get(sourceTimedID)
          } {
            res ::= new VisualLink(sourceVis, sourceKey, vi, info.key)
          }
        case _ =>
      }
      scan.sinks.foreach {
        case Scan.Link.Scan(sink) =>
          for {
            ScanInfo(sinkTimedID, sinkKey) <- scanMap.get(sink.id)
            sinkVis <- nodeMap.get(sinkTimedID)
          } {
            res ::= new VisualLink(vi, info.key, sinkVis, sinkKey)
          }
        case _ =>
      }
      res
    }

    private def removeNodeGUI(vp: VisualObj[S]): Unit = {
      aggrTable.removeTuple(vp.aggr)
      g.removeNode(vp.pNode)
      vp.scans.foreach { case (_, vs) =>
        g.removeNode(vs.pNode)
      }
      vp.params.foreach { case (_, vc) =>
        g.removeNode(vc.pNode)
      }
    }

    private def addNodeGUI(vp: VisualObj[S], links: List[VisualLink[S]], locO: Option[Point2D]): Unit = {
      val aggr  = aggrTable.addItem().asInstanceOf[AggregateItem]
      vp.aggr   = aggr

      def createNode(vn: VisualNode[S]): VisualItem = {
        vn.pNode = g.addNode()
        val _vi = _vis.getVisualItem(GROUP_GRAPH, vn.pNode)
        locO.foreach { loc =>
          _vi.setEndX(loc.getX)
          _vi.setEndY(loc.getY)
        }
        aggr.addItem(_vi)
        _vi.set(COL_NUAGES, vn)
        _vi
      }

      val vi = createNode(vp)

      def createChildNode(vc: VisualNode[S]): VisualItem = {
        val _vi = createNode(vc)
        /* val pParamEdge = */ g.addEdge(vp.pNode, vc.pNode)
        _vi
      }

      vp.scans.foreach { case (_, vScan) =>
        val vi = createChildNode(vScan)
        vi.set(VisualItem.SIZE, 0.33333f)
        // vi.set(COL_NUAGES, vScan)
      }

      vp.params.foreach { case (_, vParam) =>
        /* val vi = */ createChildNode(vParam)
        // vi.set(COL_NUAGES, vParam)
      }

      //      pMeter.foreach { pm => meterMap += pm -> res}
      // vi.set(COL_NUAGES, vp)
      // XXX this doesn't work. the vProc needs initial layout...
      //      if( p.anatomy == ProcDiff ) vi.setFixed( true )
      // procMap += p -> vProc

      links.foreach { link =>
        for {
          sourceVisScan <- link.source.scans.get(link.sourceKey)
          sinkVisScan   <- link.sink  .scans.get(link.sinkKey  )
        } {
          addEdgeGUI(sourceVisScan, sinkVisScan)
        }
      }
    }

    def addEdgeGUI(source: VisualScan[S], sink: VisualScan[S]): Unit = {
      val pEdge = g.addEdge(source.pNode, sink.pNode)
      source.sinks   += pEdge
      sink  .sources += pEdge
    }

    def removeEdgeGUI(source: VisualScan[S], sink: VisualScan[S]): Unit =
      source.sinks.find(_.getTargetNode == sink.pNode).foreach { pEdge =>
        source.sinks   -= pEdge
        sink  .sources -= pEdge
        g.removeEdge(pEdge)
      }

    //      val res = VisualProc(panel, p, pNode, aggr, vParams, pMeter, meterFactory.isDefined, soloFactory.isDefined)
    //      pMeter.foreach { pm => meterMap += pm -> res}
    //      res
    //    }
    //
    //    vi.set(COL_NUAGES, vProc)
    //    // XXX this doesn't work. the vProc needs initial layout...
    //    //      if( p.anatomy == ProcDiff ) vi.setFixed( true )
    //    procMap += p -> vProc
    //  }

    private val soloVolume  = Ref(soloAmpSpec._2)  // 0.5
    // private val soloInfo    = Ref(Option.empty[(VisualProc, Proc)])

    def setSolo(vp: VisualObj[S], onOff: Boolean): Unit = {
      //    // import DSL._
      //    if (!EventQueue.isDispatchThread) error("Must be called in event thread")
      //    soloFactory.foreach { fact => atomic { implicit t =>
      //      val info = soloInfo.swap(None)
      //      val sameProc = Some(vp) == info.map(_._1)
      //      if (sameProc && onOff) return
      //
      //      info.foreach { tup =>
      //        val (soloVP, soloP) = tup
      //        if (soloP.state.valid) {
      //          soloVP.proc ~/> soloP
      //          soloP.dispose
      //        }
      //        if (!sameProc) t.afterCommit { _ => soloVP.soloed = false}
      //      }
      //      if (onOff) {
      //        val soloP = fact.make
      //        soloP.control("amp").v = soloVolume()
      //        vp.proc ~> soloP
      //        soloP.play
      //        soloInfo.set(Some(vp -> soloP))
      //      }
      //      t.afterCommit { vp.soloed = onOff }
      //    }
      //    }
    }

    def setMasterVolume(v: Double)(implicit tx: S#Tx): Unit = ()
    //    masterProc.foreach { pMaster =>
    //      // pMaster.control("amp").v = v
    //    }

    def setSoloVolume(v: Double)(implicit tx: S#Tx): Unit = {
      val oldV = soloVolume.swap(v)(tx.peer)
      if (v == oldV) return
      // soloInfo().foreach(_._2.control("amp").v = v)
    }

    private def pack(p: Panel): p.type = {
      p.peer.setSize(p.preferredSize)
      p.peer.validate()
      p
    }

    private def createAbortBar(p: SequentialContainer)(createAction: => Unit): Unit = {
      val b = new BoxPanel(Orientation.Horizontal)
      val butCreate = /* Basic */ Button("Create")(createAction)
      butCreate.focusable = false
      b.contents += butCreate
      val strut = Swing.HStrut(8)
      // strut.background = Color.black
      b.contents += strut
      val butAbort = /* Basic */ Button("Abort")(close(p))
      butAbort.focusable = false
      b.contents += butAbort
      p.contents += b
      // b
    }

    private def close(p: Container): Unit = p.peer.getParent.remove(p.peer)

    private lazy val createGenDialog: Panel = {
      val p = new OverlayPanel()
      p.contents += listGen.component
      p.contents += Swing.VStrut(4)
      p.contents += listCol.component
      p.contents += Swing.VStrut(4)
      createAbortBar(p) {
        (listGen.guiSelection, listCol.guiSelection) match {
          case (Vec(genIdx), Vec(colIdx)) =>
            close(p)
            val displayPt = display.getAbsoluteCoordinate(p.location, null)
            atomic { implicit tx =>
              val nuages = nuagesH()
              for {
                gen <- nuages.generators.get(genIdx)
                col <- nuages.collectors.get(colIdx)
                tl  <- nuages.timeline.elem.peer.modifiableOption
              } {
                createProc(tl, gen, col, displayPt)
              }
            }
          case _ =>
        }
      }
      pack(p)
    }

    private var fltPred: VisualScan[S] = _
    private var fltSucc: VisualScan[S] = _

    private lazy val createFilterDialog = {
      val p = new OverlayPanel()
      p.contents += listFlt.component
      p.contents += Swing.VStrut(4)
      createAbortBar(p) {
        listFlt.guiSelection match {
          case Vec(fltIdx) =>
          close(p)
          val displayPt = display.getAbsoluteCoordinate(p.location, null)
          atomic { implicit tx =>
            val nuages = nuagesH()
            for {
              Proc.Obj(flt) <- nuages.filters.get(fltIdx)
              tl <- nuages.timeline.elem.peer.modifiableOption
            } {
              (fltPred.parent.objH(), fltSucc.parent.objH()) match {
                case (Proc.Obj(pred), Proc.Obj(succ)) =>
                  for {
                    predScan <- pred.elem.peer.scans.get(fltPred.key)
                    succScan <- succ.elem.peer.scans.get(fltSucc.key)
                  } {
                    createFilter(tl, predScan, succScan, flt, displayPt)
                  }
                case _ =>
              }
            }
          }
        }
      }
      pack(p)
    }

    private def exec(obj: Obj[S], key: String)(implicit tx: S#Tx): Unit =
      for (Action.Obj(self) <- obj.attr.get(key))
        self.elem.peer.execute(Action.Universe(self, workspace))

    private def prepareObj(obj: Obj[S])(implicit tx: S#Tx): Unit = exec(obj, "nuages-prepare")
    private def disposeObj(obj: Obj[S])(implicit tx: S#Tx): Unit = exec(obj, "nuages-dispose")

    def createProc(tl: Timeline.Modifiable[S], genSrc: Obj[S], colSrc: Obj[S], pt: Point2D)
                  (implicit tx: S#Tx): Unit = {
      val gen = Obj.copy(genSrc)
      val col = Obj.copy(colSrc)

      (gen, col) match {
        case (Proc.Obj(genP), Proc.Obj(colP)) =>
          val procGen = genP.elem.peer
          val procCol = colP.elem.peer
          val scanOut = procGen.scans.add("out")
          val scanIn  = procCol.scans.add("in")
          scanOut.addSink(scanIn)

        case _ =>
      }

      prepareObj(gen)
      prepareObj(col)

      val genPt = new Point2D.Double(pt.getX, pt.getY - 30)
      val colPt = new Point2D.Double(pt.getX, pt.getY + 30)
      setLocationHint(gen, genPt)
      setLocationHint(col, colPt)

      addToTimeline(tl, gen)
      addToTimeline(tl, col)
    }

    private def addToTimeline(tl: Timeline.Modifiable[S], obj: Obj[S])(implicit tx: S#Tx): Unit = {
      val imp = ExprImplicits[S]
      import imp._
      val time    = transport.position
      val span    = Span.From(time)
      val spanEx  = SpanLikeEx.newVar(span)
      tl.add(spanEx, obj)
    }

    def createFilter(tl: Timeline.Modifiable[S], pred: Scan[S], succ: Scan[S], fltSrc: Obj[S], fltPt: Point2D)
                  (implicit tx: S#Tx): Unit = {
      val flt = Obj.copy(fltSrc)

      flt match {
        case Proc.Obj(fltP) =>
          val procFlt  = fltP .elem.peer
          pred.addSink(procFlt.scans.add("in"))
          // we may handle 'sinks' here by ignoring them when they don't have an `"out"` scan.
          procFlt.scans.get("out").foreach { fltOut =>
            pred  .removeSink(succ)
            fltOut.addSink   (succ)
          }

        case _ =>
      }

      prepareObj(flt)
      setLocationHint(flt, fltPt)
      addToTimeline(tl, flt)
    }

    def showCreateGenDialog(pt: Point): Boolean = {
      requireEDT()
      showOverlayPanel(createGenDialog, pt)
    }

    def showCreateFilterDialog(pred: VisualScan[S], succ: VisualScan[S], pt: Point): Boolean = {
      requireEDT()
      fltPred = pred
      fltSucc = succ
      showOverlayPanel(createFilterDialog, pt)
    }

    //  private def procUpdate(u: Proc.Update): Unit = {
    //    if (verbose) println("" + new java.util.Date() + " procUpdate: " + u)
    //    val p = u.proc
    //    procMap.get(p).foreach { vProc =>
    //      if (u.state.valid) {
    //        if (verbose) println("procUpdate : u.state = " + u.state)
    //        val wasPlaying = vProc.state.playing
    //        vProc.state = u.state
    //        if (!wasPlaying && u.state.playing) {
    //          vProc.pMeter.foreach { pm => atomic { implicit t => if (!pm.isPlaying) pm.play}}
    //        }
    //      }
    //      if (u.controls              .nonEmpty) topControlsChanged(u.controls)
    //      if (u.audioBusesConnected   .nonEmpty) topAddEdges       (u.audioBusesConnected)
    //      if (u.audioBusesDisconnected.nonEmpty) topRemoveEdges    (u.audioBusesDisconnected)
    //    }
    //  }

    //  private def topoUpdate(u: ProcWorld.Update): Unit = {
    //    atomic { implicit t =>
    //      val toRemove = u.procsRemoved.filterNot(_.name.startsWith("$"))
    //      toRemove.foreach { p =>
    //        p.removeListener(procListener)
    //        procMap.get(p).flatMap(_.pMeter).foreach { pm =>
    //          if (pm.state.valid) pm.dispose
    //        }
    //      }
    //      val toAdd = u.procsAdded.filterNot(_.name.startsWith("$"))
    //      toAdd.foreach(_.addListener(procListener))
    //      val toAddWithMeter = toAdd.map { p =>
    //        val pMeter = (p.params.collect {
    //          case pParamBus: ProcParamAudioOutput if (pParamBus.name == "out") => p.audioOutput(pParamBus.name)
    //        }).headOption.flatMap { bus =>
    //          import DSL._
    //          (masterFactory, masterProc) match {
    //            case (Some(fact), Some(pMaster)) if (p.anatomy == ProcDiff) =>
    //              val res = fact.make
    //              // do _not_ connect to master, as currently with dispose of p
    //              // the pMaster would be disposed, too!!!
    //              bus ~> res // ~> pMaster
    //              if (p.isPlaying) res.play
    //              Some(res)
    //            case _ => meterFactory.map { fact =>
    //              val res = fact.make
    //              bus ~> res
    //              if (p.isPlaying) res.play
    //              res
    //            }
    //          }
    //        }
    //        (p, pMeter)
    //      }
    //
    //      t.afterCommit(_ => defer {
    //        if (verbose) println("" + new java.util.Date() + " topoUpdate : " + u)
    //        visDo {
    //          toRemove.foreach { p => procMap.get(p).foreach(topRemoveProc(_))}
    //          toAddWithMeter.foreach(tup => topAddProc(tup._1, tup._2))
    //        }
    //      })
    //    }
    //  }

  //    private def topRemoveProc(vProc: VisualObj[S]): Unit = {
  //      // fucking prefuse -- always trouble with the aggregates
  //      //Thread.sleep(50)
  //      //println( "REMOVE AGGR " + vProc.aggr )
  //      //      vProc.aggr.removeAllItems()
  //      aggrTable.removeTuple(vProc.aggr)
  //      //      tryRepeatedly( aggrTable.removeTuple( vProc.aggr ))
  //      g.removeNode(vProc.pNode)
  ////      vProc.params.values.foreach { vParam =>
  ////        // WE MUST NOT REMOVE THE EDGES, AS THE REMOVAL OF
  ////        // VPROC.PNODE INCLUDES ALL THE EDGES!
  ////        //         g.removeEdge( vParam.pEdge )
  ////        g.removeNode(vParam.pNode)
  ////      }
  //      //    procMap -= vProc.proc
  //    }

    //  private def topRemoveControlMap(vControl: VisualControl, vMap: VisualMapping): Unit = {
    //    g.removeEdge(vMap.pEdge)
    //    vControl.mapping = None
    //  }

    //  private def topAddControlMap(vControl: VisualControl, m: ControlBusMapping): Unit = {
    //    vControl.mapping = m match {
    //      case ma: ControlABusMapping =>
    //        val aout = ma.out // edge.out
    //        procMap.get(aout.proc).flatMap(vProc2 => {
    //          vProc2.params.get(aout.name) match {
    //            case Some(vBus: VisualAudioOutput) =>
    //              val pEdge = g.addEdge(vBus.pNode, vControl.pNode)
    //              Some(VisualMapping(ma, pEdge))
    //
    //            case _ => None
    //          }
    //        })
    //
    //      //         case _ =>
    //    }
    //  }

    //  private def topControlsChanged(controls: Map[ProcControl, ControlValue]): Unit = {
    //    val byProc = controls.groupBy(_._1.proc)
    //    byProc.foreach(tup => {
    //      val (proc, map) = tup
    //      procMap.get(proc).foreach(vProc => {
    //        map.foreach(tup2 => {
    //          val (ctrl, cv) = tup2
    //          vProc.params.get(ctrl.name) match {
    //            case Some(vControl: VisualControl) => {
    //              vControl.value = cv
    //              vControl.gliding = if (vControl.mapping.isDefined || cv.mapping.isDefined) {
    //                vControl.mapping match {
    //                  case Some(vMap) => if (cv.mapping != Some(vMap.mapping)) {
    //                    topRemoveControlMap(vControl, vMap)
    //                    cv.mapping match {
    //                      case Some(bm: ControlBusMapping) => {
    //                        topAddControlMap(vControl, bm); false
    //                      }
    //                      case Some(g: ControlGliding) => true
    //                      case _ => false
    //                    }
    //                  } else false
    //                  case None => cv.mapping match {
    //                    case Some(bm: ControlBusMapping) => {
    //                      topAddControlMap(vControl, bm); false
    //                    }
    //                    case Some(g: ControlGliding) => true
    //                    case _ => false
    //                  }
    //                }
    //              } else false
    //            }
    //            case _ =>
    //          }
    //        })
    //        // damageReport XXX
    //      })
    //    })
    //  }

  }

  private class Layout(peer: javax.swing.JComponent) extends LayoutManager {
    def layoutContainer(parent: java.awt.Container): Unit =
      peer.setBounds(new Rectangle(0, 0, parent.getWidth, parent.getHeight))

    def minimumLayoutSize  (parent: java.awt.Container): Dimension = peer.getMinimumSize
    def preferredLayoutSize(parent: java.awt.Container): Dimension = peer.getPreferredSize

    def removeLayoutComponent(comp: java.awt.Component) = ()

    def addLayoutComponent(name: String, comp: java.awt.Component) = ()
  }
}
