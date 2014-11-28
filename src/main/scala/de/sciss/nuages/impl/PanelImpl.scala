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

import java.awt.{RenderingHints, Graphics2D, Dimension, Rectangle, LayoutManager, Point, Color}
import java.awt.geom.Point2D
import javax.swing.event.{AncestorEvent, AncestorListener}
import javax.swing.JPanel

import de.sciss.desktop.{KeyStrokes, FocusType}
import de.sciss.desktop.Implicits._
import de.sciss.lucre.bitemp.{SpanLike => SpanLikeEx}
import de.sciss.lucre.stm
import de.sciss.lucre.stm.Disposable
import de.sciss.lucre.swing.{ListView, defer, deferTx, requireEDT}
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.synth.{AudioBus, Node, Txn, Synth, Sys}
import de.sciss.model.Change
import de.sciss.span.{SpanLike, Span}
import de.sciss.swingplus.DoClickAction
import de.sciss.synth
import de.sciss.synth.{addToTail, SynthGraph, proc, AudioBus => SAudioBus, message}
import de.sciss.synth.proc.{AuralObj, Action, WorkspaceHandle, Scan, ExprImplicits, AuralSystem, Transport, Timeline, Proc, Folder, Obj}

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
import scala.concurrent.stm.{TMap, Ref, TxnLocal}
import scala.swing.event.Key
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
    // transport.addObject(timelineObj)

    new Impl[S](nuagesH, nodeMap, scanMap, config, transport, aural,
                listGen = listGen, listFlt = listFlt, listCol = listCol)
      .init(timelineObj)
  }

  private final val GROUP_NODES   = "graph.nodes"
  private final val GROUP_EDGES   = "graph.edges"

  private final val AGGR_PROC     = "aggr"
  private final val ACTION_LAYOUT = "layout"
  private final val ACTION_COLOR  = "color"
  private final val LAYOUT_TIME   = 50

  private def mkListView[S <: Sys[S]](folder: Folder[S])(implicit tx: S#Tx, cursor: stm.Cursor[S]) = {
    import proc.Implicits._
    val h = ListView.Handler[S, Obj[S], Obj.Update[S]] { implicit tx => obj => obj.name } (_ => (_, _) => None)
    implicit val ser = de.sciss.lucre.expr.List.serializer[S, Obj[S], Obj.Update[S]]
    val res = ListView[S, Obj[S], Obj.Update[S], String](folder, h)
    deferTx {
      val c = res.view
      c.background = Color.black
      c.foreground = Color.white
      c.selectIndices(0)
    }
    res
  }

  private final class VisualLink[S <: Sys[S]](val source: VisualObj[S], val sourceKey: String,
                                              val sink  : VisualObj[S], val sinkKey  : String)

  private final case class ScanInfo[S <: Sys[S]](id: S#ID, key: String)

  // nodeMap: uses timed-id as key
  private final class Impl[S <: Sys[S]](nuagesH: stm.Source[S#Tx, Nuages[S]],
                                        nodeMap: stm.IdentifierMap[S#ID, S#Tx, VisualObj[S]],
                                        scanMap: stm.IdentifierMap[S#ID, S#Tx, ScanInfo[S]],
                                        val config   : Nuages.Config,
                                        val transport: Transport[S],
                                        val aural    : AuralSystem,
                                        listGen: ListView[S, Obj[S], Obj.Update[S]],
                                        listFlt: ListView[S, Obj[S], Obj.Update[S]],
                                        listCol: ListView[S, Obj[S], Obj.Update[S]])
                                       (implicit cursor: stm.Cursor[S], workspace: WorkspaceHandle[S])
    extends NuagesPanel[S] with ComponentHolder[Component] {
    panel =>

    import NuagesPanel._
    import cursor.{step => atomic}

    private var _vis: Visualization = _
    private var _dsp: Display       = _
    private var g   : Graph         = _
    private var vg  : VisualGraph   = _

    private var aggrTable: AggregateTable = _

    //  var transition: Double => Transition = (_) => Instant

    private var masterBusVar : Option[SAudioBus] = None

    private val locHintMap = TxnLocal(Map.empty[Obj[S], Point2D])

    private var overlay = Option.empty[Component]

    private var timelineObserver : Disposable[S#Tx] = _
    private var transportObserver: Disposable[S#Tx] = _
    private val auralObserver = Ref(Option.empty[Disposable[S#Tx]])
    private val auralTimeline = Ref(Option.empty[AuralObj.Timeline[S]])

    private val auralToViewMap  = TMap.empty[AuralObj[S], VisualObj[S]]
    private val viewToAuralMap  = TMap.empty[VisualObj[S], AuralObj[S]]

    def display      : Display        = _dsp
    def visualization: Visualization  = _vis

    def masterBus: Option[SAudioBus] = masterBusVar

    def setLocationHint(p: Obj[S], loc: Point2D)(implicit tx: S#Tx): Unit =
      locHintMap.transform(_ + (p -> loc))(tx.peer)

    def nuages(implicit tx: S#Tx): Nuages[S] = nuagesH()

    def dispose()(implicit tx: S#Tx): Unit = {
      implicit val itx = tx.peer
      deferTx(stopAnimation())
      clearSolo()
      transportObserver.dispose()
      timelineObserver .dispose()
      disposeAuralObserver()
      transport.dispose()
      auralToViewMap.foreach { case (_, vp) =>
        vp.dispose()
      }
      viewToAuralMap.clear()
      auralToViewMap.clear()
      nodeMap  .dispose()
      scanMap  .dispose()
    }

    private def disposeAuralObserver()(implicit tx: S#Tx): Unit = {
      auralTimeline.set(None)(tx.peer)
      auralObserver.swap(None)(tx.peer).foreach(_.dispose())
    }

    def init(timeline: Timeline.Obj[S])(implicit tx: S#Tx): this.type = {
      deferTx(guiInit())
      transportObserver = transport.react { implicit tx => {
        case Transport.ViewAdded(_, auralTL: AuralObj.Timeline[S]) =>
          val obs = auralTL.contents.react { implicit tx => {
            case AuralObj.Timeline.ViewAdded  (_, timed, view) =>
              if (config.meters) nodeMap.get(timed).foreach { vp =>
                auralObjAdded(vp, view)
              }
            case AuralObj.Timeline.ViewRemoved(_, view) =>
              auralObjRemoved(view)
          }}
          disposeAuralObserver()
          auralTimeline.set(Some(auralTL))(tx.peer)
          auralObserver.set(Some(obs    ))(tx.peer)

        case Transport.ViewRemoved(_, auralTL: AuralObj.Timeline[S]) =>
          disposeAuralObserver()

        case _ =>
      }}
      transport.addObject(timeline)

      timelineObserver = timeline.elem.peer.changed.react { implicit tx => upd =>
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
      _dsp = new Display(_vis) {
        override def setRenderingHints(g: Graphics2D): Unit = {
          super.setRenderingHints(g)
          g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
            if (m_highQuality) RenderingHints.VALUE_STROKE_PURE else RenderingHints.VALUE_STROKE_NORMALIZE)
        }
      }

      g     = new Graph
      vg    = _vis.addGraph(GROUP_GRAPH, g)
      vg.addColumn(COL_NUAGES, classOf[AnyRef])
      aggrTable = _vis.addAggregates(AGGR_PROC)
      aggrTable .addColumn(VisualItem.POLYGON, classOf[Array[Float]])

      val procRenderer = new NuagesProcRenderer(50)
      val edgeRenderer = new EdgeRenderer(Constants.EDGE_TYPE_LINE, Constants.EDGE_ARROW_FORWARD) {
        // same as original method but with much larger border to ease mouse control
        override def locatePoint(p: Point2D, item: VisualItem): Boolean = {
          val s = getShape(item)
          s != null && {
            val width     = math.max(20 /* 2 */, getLineWidth(item))
            val halfWidth = width / 2.0
            s.intersects(p.getX - halfWidth, p.getY - halfWidth, width, width)
          }
        }
      }
      // edgeRenderer.setDefaultLineWidth(2)
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
      _dsp.setSize(960, 640)
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
*/

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
      val id    = timed.id
      val obj   = timed.value
      val vp    = VisualObj[S](this, timed.span, obj, pMeter = None, meter = config.meters,
        solo = config.soloChannels.isDefined)
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

      if (vp.meter) for {
        auralTL  <- auralTimeline.get(tx.peer)
        auralObj <- auralTL.getView(timed)
      } {
        auralObjAdded(vp, auralObj)
      }

      deferTx(visDo(addNodeGUI(vp, links, locO)))
    }

    // makes the meter synth
    private def auralObjAdded(vp: VisualObj[S], aural: AuralObj[S])(implicit tx: S#Tx): Unit =
      getScanOutData(aural).foreach { case (bus, node) =>
        // println(s"For $aural - bus is $bus")
        val meterGraph = SynthGraph {
          import synth._; import ugen._
          val meterTr = Impulse.kr(1000.0 / LAYOUT_TIME)
          val sig     = In.ar("in".kr, bus.numChannels)
          val peak    = Peak.kr(sig, meterTr) // .outputs
          val peakM   = Reduce.max(peak)
          // peakM.poll(1, "peak")
          SendTrig.kr(meterTr, peakM)
          // vp.meterUpdate(vals(0).toFloat)
        }
        val meterSynth = Synth.play(meterGraph, Some("meter"))(node.server.defaultGroup, addAction = addToTail,
          dependencies = node :: Nil)
        meterSynth.read(bus -> "in")
        val NodeID = meterSynth.peer.id
        val trigResp = message.Responder.add(node.server.peer) {
          case m @ message.Trigger(NodeID, 0, peak: Float) =>
            defer(vp.meterUpdate(peak))
        }
        // Responder.add is non-transactional. Thus, if the transaction fails, we need to remove it.
        scala.concurrent.stm.Txn.afterRollback { _ =>
          trigResp.remove()
        } (tx.peer)
        meterSynth.onEnd(trigResp.remove())
        vp.meterSynth = Some(meterSynth)

        auralToViewMap.put(aural, vp)(tx.peer)
        viewToAuralMap.put(vp, aural)(tx.peer)
    }

    private def auralObjRemoved(aural: AuralObj[S])(implicit tx: S#Tx): Unit = {
      auralToViewMap.remove(aural)(tx.peer).foreach { vp =>
        viewToAuralMap.remove(vp)(tx.peer)
        vp.meterSynth = None
      }
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
        // println(s"Disposing ${vp.meterSynth}")
        vp.dispose()
        disposeObj(obj)

        // note: we could look for `solo` and clear it
        // if relevant; but the bus-reader will automatically
        // go to dummy, so let's just save the effort.
        // orphaned solo will be cleared when calling
        // `setSolo` another time or upon frame disposal.
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
          //          _vi.setStartX(loc.getX)
          //          _vi.setStartY(loc.getX)
          //          _vi.setX   (loc.getX)
          //          _vi.setY   (loc.getX)
          _vi.setEndX(loc.getX)
          _vi.setEndY(loc.getY)
        }
        aggr.addItem(_vi)
        _vi.set(COL_NUAGES, vn)
        // if (vn.name.startsWith("O-")) _vi.setFixed(true)  // ...hack
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

    private val soloVolume  = Ref(soloAmpSpec._2)  // 0.5

    private val soloInfo    = Ref(Option.empty[(VisualObj[S], Synth)])

    private def getScanOutData(aural: AuralObj[S])(implicit tx: S#Tx): Option[(AudioBus, Node)] = aural match {
      case ap: AuralObj.Proc[S] =>
        val d = ap.data
        for {
          either  <- d.getScan(Proc.Obj.scanMainOut)
          nodeRef <- d.nodeOption
        } yield {
          val bus   = either.fold(identity, _.bus)
          val node  = nodeRef.node
          (bus, node)
        }
      case _ => None
    }

    private def clearSolo()(implicit tx: S#Tx): Unit = {
      val oldInfo = soloInfo.swap(None)(tx.peer)
      oldInfo.foreach { case (oldVP, oldSynth) =>
        oldSynth.dispose()
        deferTx(oldVP.soloed = false)
      }
    }

    def setSolo(vp: VisualObj[S], onOff: Boolean): Unit = config.soloChannels.foreach { outChans =>
      requireEDT()
      atomic { implicit tx =>
        implicit val itx = tx.peer
        clearSolo()
        if (onOff) for {
          auralProc   <- viewToAuralMap.get(vp)
          (bus, node) <- getScanOutData(auralProc)
        } {
          val sg = SynthGraph {
            import synth._; import ugen._
            val numIn     = bus.numChannels
            val numOut    = outChans.size
            // println(s"numIn = $numIn, numOut = $numOut")
            val in        = In.ar("in".kr, numIn)
            val amp       = "amp".kr(1f)
            val sigOut    = SplayAz.ar(numOut, in)
            val mix       = sigOut * amp
            outChans.zipWithIndex.foreach { case (ch, idx) =>
              ReplaceOut.ar(ch, mix \ idx)
            }
          }
          val soloSynth = Synth.play(sg, Some("solo"))(target = node.server.defaultGroup, addAction = addToTail,
            args = "amp" -> soloVolume() :: Nil, dependencies = node :: Nil)
          soloSynth.read(bus -> "in")
          soloInfo.set(Some(vp -> soloSynth))
        }
        deferTx(vp.soloed = onOff)
      }
    }

    private val _masterSynth = Ref(Option.empty[Synth])

    def masterSynth(implicit tx: Txn): Option[Synth] = _masterSynth.get(tx.peer)
    def masterSynth_=(value: Option[Synth])(implicit tx: Txn): Unit =
      _masterSynth.set(value)(tx.peer)

    def setMasterVolume(v: Double)(implicit tx: S#Tx): Unit =
      _masterSynth.get(tx.peer).foreach(_.set(true, "amp" -> v))

    //    masterProc.foreach { pMaster =>
    //      // pMaster.control("amp").v = v
    //    }

    def setSoloVolume(v: Double)(implicit tx: S#Tx): Unit = {
      implicit val itx = tx.peer
      val oldV = soloVolume.swap(v)
      if (v == oldV) return
      soloInfo().foreach(_._2.set(true, "amp" -> v))
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
      butAbort.addAction("press", focus = FocusType.Window, action = new DoClickAction(butAbort) {
        accelerator = Some(KeyStrokes.plain + Key.Escape)
      })
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
