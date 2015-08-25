/*
 *  PanelImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2015 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import java.awt.geom.Point2D
import java.awt.{Color, Dimension, Graphics2D, LayoutManager, Point, Rectangle, RenderingHints}
import javax.swing.JPanel
import javax.swing.event.{AncestorEvent, AncestorListener}

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Obj, Disposable, TxnLike}
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.swing.{ListView, defer, deferTx, requireEDT}
import de.sciss.lucre.synth.{AudioBus, Node, Synth, Sys, Txn}
import de.sciss.model.Change
import de.sciss.span.{Span, SpanLike}
import de.sciss.synth.proc.{Action, AuralObj, AuralSystem, Folder, Proc, Scan, Timeline, Transport, WorkspaceHandle}
import de.sciss.synth.{SynthGraph, addToTail, message}
import prefuse.action.assignment.ColorAction
import prefuse.action.layout.graph.ForceDirectedLayout
import prefuse.action.{ActionList, RepaintAction}
import prefuse.activity.Activity
import prefuse.controls.{Control, WheelZoomControl, ZoomControl}
import prefuse.data.event.TupleSetListener
import prefuse.data.tuple.{DefaultTupleSet, TupleSet}
import prefuse.data.{Graph, Table, Tuple}
import prefuse.render.{DefaultRendererFactory, EdgeRenderer, PolygonRenderer}
import prefuse.util.ColorLib
import prefuse.visual.expression.InGroupPredicate
import prefuse.visual.{AggregateTable, NodeItem, VisualGraph, VisualItem}
import prefuse.{Constants, Display, Visualization}

import scala.collection.breakOut
import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.{Ref, TMap, TxnExecutor, TxnLocal}
import scala.swing.{Component, Swing}
import scala.util.control.NonFatal

object PanelImpl {
  var DEBUG = false

  def apply[S <: Sys[S]](nuages: Nuages[S], config: Nuages.Config)
                        (implicit tx: S#Tx, aural: AuralSystem, cursor: stm.Cursor[S],
                         workspace: WorkspaceHandle[S]): NuagesPanel[S] = {
    val nuagesH       = tx.newHandle(nuages)

    val listGen       = mkListView(nuages.generators)
    val listFlt1      = mkListView(nuages.filters   )
    val listCol1      = mkListView(nuages.collectors)
    val listFlt2      = mkListView(nuages.filters   )
    val listCol2      = mkListView(nuages.collectors)
    val listMacro     = mkListView(nuages.macros    )

    val nodeMap       = tx.newInMemoryIDMap[VisualObj[S]]
    val scanMap       = tx.newInMemoryIDMap[ScanInfo [S]]
    val missingScans  = tx.newInMemoryIDMap[List[VisualControl[S]]]
    val transport     = Transport[S](aural)
    val timelineObj   = nuages.timeline
    // transport.addObject(timelineObj)

    new Impl[S](nuagesH, nodeMap, scanMap, missingScans, config, transport, aural,
                listGen = listGen, listFlt1 = listFlt1, listCol1 = listCol1, listFlt2 = listFlt2, listCol2 = listCol2,
                listMacro = listMacro)
      .init(timelineObj)
  }

  final         val GROUP_NODES   = "graph.nodes"
  private final val GROUP_EDGES   = "graph.edges"

  private final val AGGR_PROC     = "aggr"
  private final val ACTION_LAYOUT = "layout"
  private final val ACTION_COLOR  = "color"
  private final val LAYOUT_TIME   = 50

  private def mkListView[S <: Sys[S]](folderOpt: Option[Folder[S]])(implicit tx: S#Tx, cursor: stm.Cursor[S]) = {
    val h = ListView.Handler[S, Obj[S], Obj.Update[S]] { implicit tx => obj => obj.name } (_ => (_, _) => None)
    implicit val ser = de.sciss.lucre.expr.List.serializer[S, Obj[S], Obj.Update[S]]
    // val res = ListView[S, Obj[S], Obj.Update[S], String](folder, h)
    val res = ListView.empty[S, Obj[S], Obj.Update[S], String](h)
    deferTx {
      val c = res.view
      c.background = Color.black
      c.foreground = Color.white
      c.selectIndices(0)
    }
    res.list = folderOpt
    res
  }

  private final class VisualLink[S <: Sys[S]](val source: VisualObj[S], val sourceKey: String,
                                              val sink  : VisualObj[S], val sinkKey  : String, val isScan: Boolean)

  /* Contains the `id` of the parent `timed` object, and the scan key */
  private final case class ScanInfo[S <: Sys[S]](timedID: S#ID, key: String, isInput: Boolean)

  // nodeMap: uses timed-id as key
  private final class Impl[S <: Sys[S]](nuagesH: stm.Source[S#Tx, Nuages[S]],
                                        nodeMap: stm.IdentifierMap[S#ID, S#Tx, VisualObj[S]],
                                        scanMap: stm.IdentifierMap[S#ID, S#Tx, ScanInfo[S]],
                                        missingScans: stm.IdentifierMap[S#ID, S#Tx, List[VisualControl[S]]],
                                        val config   : Nuages.Config,
                                        val transport: Transport[S],
                                        val aural    : AuralSystem,
                                        listGen  : ListView[S, Obj[S], Obj.Update[S]],
                                        listFlt1 : ListView[S, Obj[S], Obj.Update[S]],
                                        listCol1 : ListView[S, Obj[S], Obj.Update[S]],
                                        listFlt2 : ListView[S, Obj[S], Obj.Update[S]],
                                        listCol2 : ListView[S, Obj[S], Obj.Update[S]],
                                        listMacro: ListView[S, Obj[S], Obj.Update[S]])
                                       (implicit val cursor: stm.Cursor[S], workspace: WorkspaceHandle[S])
    extends NuagesPanel[S] with ComponentHolder[Component] {
    panel =>

    import cursor.{step => atomic}
    import de.sciss.nuages.NuagesPanel._

    private var _vis: Visualization = _
    private var _dsp: Display       = _
    private var _g  : Graph         = _
    private var _vg : VisualGraph   = _

    private var _aggrTable: AggregateTable = _

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
    def graph        : Graph          = _g
    def visualGraph  : VisualGraph    = _vg
    def aggrTable    : AggregateTable = _aggrTable

    def setLocationHint(p: Obj[S], loc: Point2D)(implicit tx: S#Tx): Unit =
      locHintMap.transform(_ + (p -> loc))(tx.peer)

    def nuages(implicit tx: S#Tx): Nuages[S] = nuagesH()

    private var keyControl: Control with Disposable[S#Tx] = _

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
      nodeMap       .dispose()
      scanMap       .dispose()
      missingScans  .dispose()

      keyControl    .dispose()
    }

    private def disposeAuralObserver()(implicit tx: S#Tx): Unit = {
      auralTimeline.set (None)(tx.peer)
      auralObserver.swap(None)(tx.peer).foreach(_.dispose())
    }

    def init(tlObj: Timeline[S])(implicit tx: S#Tx): this.type = {
      keyControl = KeyControl(this)
      deferTx(guiInit())
      transportObserver = transport.react { implicit tx => {
        case Transport.ViewAdded(_, auralTL: AuralObj.Timeline[S]) =>
          val obs = auralTL.contents.react { implicit tx => {
            case AuralObj.Timeline.ViewAdded  (_, timed, view) =>
              nodeMap.get(timed).foreach { vp =>
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
      transport.addObject(tlObj)

      val tl = tlObj
      timelineObserver = tl.changed.react { implicit tx => upd =>
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
                    case Proc.OutputChange(key, scan, scanChanges) =>
                      def withScans(sink: Scan.Link[S])(fun: (VisualScan[S], VisualScan[S]) => Unit): Unit =
                        for {
                          sinkInfo <- scanMap.get(sink.id)
                          sinkVis  <- nodeMap.get(sinkInfo.timedID)
                        } deferVisTx {
                          for {
                            sourceVisScan <- visObj .outputs.get(key)
                            sinkVisScan   <- sinkVis.inputs .get(sinkInfo.key)
                          } {
                            fun(sourceVisScan, sinkVisScan)
                          }
                        }

                      scanChanges.foreach {
                        case Scan.Added  (sink) => withScans(sink)(addScanScanEdgeGUI)
                        case Scan.Removed(sink) => withScans(sink)(removeEdgeGUI     )
                        case _ =>
                      }

                    case other => if (DEBUG) println(s"OBSERVED: Timeline.Element - ProcChange($other)")
                  }

                case Obj.AttrRemoved(key, elem) =>
                  deferVisTx {
                    visObj.params.get(key).foreach { vc =>
                      removeControlGUI(visObj, vc)
                    }
                  }

                case Obj.AttrAdded(key, elem) =>
                  elem match {
                    case DoubleElem.Obj(dObj) => addScalarControl(visObj, key, dObj)
                    case Scan      .Obj(sObj) => addScanControl  (visObj, key, sObj)
                    case _ =>
                  }

                case other => if (DEBUG) println(s"OBSERVED: Timeline.Element - $other")
              }
            }

          case other => if (DEBUG) println(s"OBSERVED: $other")
        }
      }

      tl.intersect(transport.position).foreach { case (span, elems) =>
        elems.foreach(addNode(span, _))
      }
      this
    }

    // ---- constructor ----
    private def guiInit(): Unit = {
      _vis = new Visualization
      _dsp = new Display(_vis) {
        // setFocusable(true)

        override def setRenderingHints(g: Graphics2D): Unit = {
          super.setRenderingHints(g)
          g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
            if (m_highQuality) RenderingHints.VALUE_STROKE_PURE else RenderingHints.VALUE_STROKE_NORMALIZE)
        }
      }

      _g     = new Graph
      _vg    = _vis.addGraph(GROUP_GRAPH, _g)
      _vg.addColumn(COL_NUAGES, classOf[AnyRef])
      _aggrTable = _vis.addAggregates(AGGR_PROC)
      _aggrTable .addColumn(VisualItem.POLYGON, classOf[Array[Float]])

      val procRenderer = new NuagesShapeRenderer(50)
      val edgeRenderer = new EdgeRenderer(Constants.EDGE_TYPE_LINE, Constants.EDGE_ARROW_FORWARD) {
//        override def render(g: Graphics2D, item: VisualItem): Unit = {
//          g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
//          super.render(g, item)
//        }

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
      actionNodeStroke.add(VisualItem.HIGHLIGHT, ColorLib.rgb(255, 255, 0))
      // actionNodeStroke.add(VisualItem.HIGHLIGHT, ColorLib.rgb(220, 220, 0))
      val actionNodeFill    = new ColorAction(GROUP_NODES, VisualItem.FILLCOLOR  , ColorLib.rgb(0, 0, 0))
      actionNodeFill.add(VisualItem.HIGHLIGHT, ColorLib.rgb(63, 63, 0))
      val actionTextColor   = new ColorAction(GROUP_NODES, VisualItem.TEXTCOLOR  , ColorLib.rgb(255, 255, 255))

      val actionEdgeColor   = new ColorAction(GROUP_EDGES, VisualItem.STROKECOLOR, ColorLib.rgb(255, 255, 255))
      val actionAggrFill    = new ColorAction(AGGR_PROC  , VisualItem.FILLCOLOR  , ColorLib.rgb(80, 80, 80))
      // actionAggrFill.add(VisualItem.HIGHLIGHT, ColorLib.rgb(220, 220, 0))
      val actionAggrStroke  = new ColorAction(AGGR_PROC  , VisualItem.STROKECOLOR, ColorLib.rgb(255, 255, 255))

      val lay = new ForceDirectedLayout(GROUP_GRAPH)
      val sim = lay.getForceSimulator
      val forces = sim.getForces.map { f => (f.getClass.getSimpleName, f) } (breakOut)

      val forceMap = Map(
        // ("NBodyForce" , "GravitationalConstant") -> 0f, // -0.01f,
        ("NBodyForce", "Distance") -> 200.0f
        // ("NBodyForce" , "BarnesHutTheta"       ) -> 0.4f,
        // ("DragForce"  , "DragCoefficient"      ) -> 0.015f,
      )

      sim.getForces.foreach { force =>
        val fName = force.getClass.getSimpleName
        for (i <- 0 until force.getParameterCount) {
          val pName = force.getParameterName(i)
          forceMap.get((fName, pName)).foreach { value =>
            force.setParameter(i, value)
          }
        }
      }

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
      // actionLayout.add(actionNodeFill)
      actionLayout.add(new RepaintAction())
      _vis.putAction(ACTION_LAYOUT, actionLayout)
      _vis.alwaysRunAfter(ACTION_COLOR, ACTION_LAYOUT)

      // ------------------------------------------------

      // initialize the display
      _dsp.setSize(960, 640)
      _dsp.addControlListener(new ZoomControl     ())
      _dsp.addControlListener(new WheelZoomControl())
      _dsp.addControlListener(new PanControl        )
      _dsp.addControlListener(new DragControl     (_vis))
      _dsp.addControlListener(new ClickControl    (this))
      _dsp.addControlListener(new ConnectControl  (this))
      _dsp.addControlListener(keyControl)
      _dsp.setHighQuality(true)
      new GlobalControl(this)

      // ---- rubber band test ----
      mkRubberBand(rf)

      // ------------------------------------------------

      edgeRenderer.setHorizontalAlignment1(Constants.CENTER)
      edgeRenderer.setHorizontalAlignment2(Constants.CENTER)
      edgeRenderer.setVerticalAlignment1  (Constants.CENTER)
      edgeRenderer.setVerticalAlignment2  (Constants.CENTER)

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

    private def mkRubberBand(rf: DefaultRendererFactory): Unit = {
      val selectedItems = new DefaultTupleSet
      _vis.addFocusGroup(GROUP_SELECTION, selectedItems)
      selectedItems.addTupleSetListener(new TupleSetListener {
        def tupleSetChanged(ts: TupleSet, add: Array[Tuple], remove: Array[Tuple]): Unit = {
          add.foreach {
            case vi: VisualItem => vi.setHighlighted(true)
          }
          remove.foreach {
            case vi: VisualItem => vi.setHighlighted(false)
          }
          _vis.run(ACTION_COLOR)
        }
      })

      val rubberBandTable = new Table
      rubberBandTable.addColumn(VisualItem.POLYGON, classOf[Array[Float]])
      rubberBandTable.addRow()
      _vis.add("rubber", rubberBandTable)
      val rubberBand = _vis.getVisualGroup("rubber").tuples().next().asInstanceOf[VisualItem]
      rubberBand.set(VisualItem.POLYGON, new Array[Float](8))
      rubberBand.setStrokeColor(ColorLib.color(ColorLib.getColor(255, 0, 0)))

      // render the rubber band with the default polygon renderer
      val rubberBandRenderer = new PolygonRenderer(Constants.POLY_TYPE_LINE)
      rf.add(new InGroupPredicate("rubber"), rubberBandRenderer)

      _dsp.addControlListener(new RubberBandSelect(rubberBand))
    }

    def selection: Set[VisualNode[S]] = {
      requireEDT()
      val selectedItems = _vis.getGroup(GROUP_SELECTION)
      import scala.collection.JavaConversions._
      selectedItems.tuples().flatMap {
        case ni: NodeItem =>
          ni.get(COL_NUAGES) match {
            case vn: VisualNode[S] => Some(vn)
            case _ => None
          }
        case _ => None
      } .toSet
    }

    def showOverlayPanel(p: OverlayPanel, ptOpt: Option[Point] = None): Boolean = {
      if (overlay.isDefined) return false
      val pp = p.peer
      val c = component
      val dw = c.peer.getWidth  - pp.getWidth
      val dh = c.peer.getHeight - pp.getHeight
      ptOpt.fold {
        pp.setLocation(math.max(0, dw/2), math.min(dh, dh/2))
      } { pt =>
        val x = math.max(0, math.min(pt.getX.toInt , dw))
        val y = math.min(math.max(0, pt.getY.toInt), dh) // make sure bottom is visible
        pp.setLocation(x, y)
      }
      //println( "aqui " + p.getX + ", " + p.getY + ", " + p.getWidth + ", " + p.getHeight )
      c.peer.add(pp, 0)
      c.revalidate()
      c.repaint()
      pp.addAncestorListener(new AncestorListener {
        def ancestorAdded(e: AncestorEvent) = ()

        def ancestorRemoved(e: AncestorEvent): Unit = {
          pp.removeAncestorListener(this)
          if (Some(p) == overlay) {
            overlay = None
            display.requestFocus()
          }
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

      //      atomic { implicit t =>
      //        //      factoryManager.stopListening
      //        //      world.removeListener(topoListener)
      //        //      masterProc.foreach { pMaster =>
      //        //        pMaster.dispose
      //        //        pMaster.group.free(true)
      //        //      }
      //        // masterBus.foreach(_.free())
      //      }
    }

    private[this] val guiCode = TxnLocal(init = Vector.empty[() => Unit], afterCommit = handleGUI)

    private[this] def handleGUI(seq: Vec[() => Unit]): Unit = {
      def exec(): Unit = _vis.synchronized {
        stopAnimation()
        seq.foreach { fun =>
          try {
            fun()
          } catch {
            case NonFatal(e) => e.printStackTrace()
          }
        }
        startAnimation()
      }

      defer(exec())
    }

    def deferVisTx(thunk: => Unit)(implicit tx: TxnLike): Unit =
      guiCode.transform(_ :+ (() => thunk))(tx.peer)

    @inline private def stopAnimation(): Unit = {
      _vis.cancel(ACTION_COLOR)
      _vis.cancel(ACTION_LAYOUT)
    }

    @inline private def startAnimation(): Unit =
      _vis.run(ACTION_COLOR)

    //    private def visDo[A](code: => A): A =
    //      _vis.synchronized {
    //        stopAnimation()
    //        try {
    //          code
    //        } finally {
    //          startAnimation()
    //        }
    //      }

    def addNode(span: SpanLike, timed: Timeline.Timed[S])(implicit tx: S#Tx): Unit = {
      val id    = timed.id
      val obj   = timed.value
      val vp    = VisualObj[S](this, timed.span, obj, hasMeter = config.meters, hasSolo = config.soloChannels.isDefined)
      nodeMap.put(id, vp)
      val locO  = locHintMap.getAndTransform(_ - obj)(tx.peer).get(obj)

      var links: List[VisualLink[S]] = obj match {
        case Proc.Obj(objT) =>
          val proc  = objT
          val l1 = proc.inputs.iterator.flatMap { case (key, scan) =>
            val info = new ScanInfo(id, key, isInput = true)
            addScan(vp, scan, info)
          } .toList

          val l2 = proc.outputs.iterator.flatMap { case (key, scan) =>
            val info = new ScanInfo(id, key, isInput = false)
            addScan(vp, scan, info)
          } .toList

          l1 ++ l2

        case _ => Nil
      }

      // check existing mappings; establish visual links or remember missing scans
      vp.params.foreach { case (sinkKey, vSink) =>
        vSink.mapping.foreach { m =>
          assert(m.source.isEmpty)  // must be missing at this stage
          val scan  = m.scan
          val sid   = scan.id
          scanMap.get(sid).fold {
            val list  = vSink :: missingScans.getOrElse(sid, Nil)
            missingScans.put(sid, list)
          } { info =>
            for {
              vObj    <- nodeMap.get(info.timedID)
              // vScan   <- vObj.scans.get(info.key)
            } {
              assignMapping(source = scan, vSink = vSink)
              links ::= new VisualLink(vObj, info.key, vp /* aka vCtl.parent */, sinkKey, isScan = false)
            }
          }
        }
      }

      for {
        auralTL  <- auralTimeline.get(tx.peer)
        auralObj <- auralTL.getView(timed)
      } {
        auralObjAdded(vp, auralObj)
      }

      deferVisTx(addNodeGUI(vp, links, locO))
    }

    def addScalarControl(visObj: VisualObj[S], key: String, dObj: DoubleObj[S])(implicit tx: S#Tx): Unit = {
      val vc = VisualControl.scalar(visObj, key, dObj)
      addControl(visObj, vc)
    }

    def addScanControl(visObj: VisualObj[S], key: String, sObj: Scan[S])(implicit tx: S#Tx): Unit = {
      implicit val itx = tx.peer
      val vc    = VisualControl.scan(visObj, key, sObj)
      val scan  = sObj
      assignMapping(source = scan, vSink = vc)
      addControl(visObj, vc)
    }

    private def assignMapping(source: Scan[S], vSink: VisualControl[S])(implicit tx: S#Tx): Unit = {
      implicit val itx = tx.peer
      for {
        info    <- scanMap.get(source.id)
        vObj    <- nodeMap.get(info.timedID)
        vScan   <- vObj.outputs.get(info.key)
        m       <- vSink.mapping
      } {
        m.source = Some(vScan)  // XXX TODO -- not cool, should be on the EDT
        for {
          aural <- viewToAuralMap.get(vObj)
          (bus, node) <- getAuralScanData(aural, info.key)
        } {
          m.synth() = Some(mkMeter(bus, node)(vSink.value = _))
        }
      }
    }

    // simple cache from num-channels to graph
    private var meterGraphMap = Map.empty[Int, SynthGraph]

    private def mkMeter(bus: AudioBus, node: Node)(fun: Float => Unit)(implicit tx: S#Tx): Synth = {
      val numCh       = bus.numChannels
      val meterGraph  = meterGraphMap.getOrElse(numCh, {
        val res = SynthGraph {
          import de.sciss.synth._
          import de.sciss.synth.ugen._
          val meterTr = Impulse.kr(1000.0 / LAYOUT_TIME)
          val sig     = In.ar("in".kr, numCh)
          val peak    = Peak.kr(sig, meterTr) // .outputs
          val peakM   = Reduce.max(peak)
          SendTrig.kr(meterTr, peakM)
        }
        meterGraphMap += numCh -> res
        res
      })
      val meterSynth = Synth.play(meterGraph, Some("meter"))(node.server.defaultGroup, addAction = addToTail,
        dependencies = node :: Nil)
      meterSynth.read(bus -> "in")
      val NodeID = meterSynth.peer.id
      val trigResp = message.Responder.add(node.server.peer) {
        case m @ message.Trigger(NodeID, 0, peak: Float) =>
          defer(fun(peak))
      }
      // Responder.add is non-transactional. Thus, if the transaction fails, we need to remove it.
      scala.concurrent.stm.Txn.afterRollback { _ =>
        trigResp.remove()
      } (tx.peer)
      meterSynth.onEnd(trigResp.remove())
      meterSynth
    }

    private def addControl(visObj: VisualObj[S], vc: VisualControl[S])(implicit tx: S#Tx): Unit = {
      // val key     = vc.key
      // val locOpt  = locHintMap.get(tx.peer).get(visObj -> key)
      // println(s"locHintMap($visObj -> $key) = $locOpt")
      deferVisTx {
        addControlGUI(visObj, vc /* , locOpt */)
      }
    }

    // makes the meter synth
    private def auralObjAdded(vp: VisualObj[S], aural: AuralObj[S])(implicit tx: S#Tx): Unit = {
      if (config.meters) getAuralScanData(aural).foreach { case (bus, node) =>
        val meterSynth = mkMeter(bus, node)(vp.meterUpdate)
        vp.meterSynth = Some(meterSynth)
      }
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
            val proc  = objT
            proc.inputs.iterator.foreach { case (_, scan) =>
              scanMap.remove(scan.id)
            }
            proc.outputs.iterator.foreach { case (_, scan) =>
              scanMap.remove(scan.id)
            }
            // XXX TODO -- clean up missing controls

          case _ =>
        }

        deferVisTx(removeNodeGUI(vp))
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

    // looks for existing sinks and sources of a scan that
    // are already represented in the GUI. for those, creates
    // visual links and returns them (they'll have to be
    // "materialized" in terms of prefuse-edges later).
    //
    // resolves entries in missingScans as well.
    private def addScan(vi: VisualObj[S], scan: Scan[S], info: ScanInfo[S])(implicit tx: S#Tx): List[VisualLink[S]] = {
      val sid = scan.id
      scanMap.put(sid, info)  // stupid look-up
      var res = List.empty[VisualLink[S]]
      scan.iterator.foreach {
        case Scan.Link.Scan(target) =>
          for {
            ScanInfo(targetTimedID, targetKey, _) <- scanMap.get(target.id)
            targetVis <- nodeMap.get(targetTimedID)
          } {
            import info.isInput
            val sourceVis = if (isInput) targetVis else vi
            val sourceKey = if (isInput) targetKey else info.key
            val sinkVis   = if (isInput) vi else targetVis
            val sinkKey   = if (isInput) info.key else targetKey

            res ::= new VisualLink(sourceVis, sourceKey, sinkVis, sinkKey, isScan = true)
          }
        case _ =>
      }

      missingScans.get(sid).foreach { controls =>
        missingScans.remove(sid)
        controls  .foreach { ctl =>
          assignMapping(source = scan, vSink = ctl)
          res ::= new VisualLink(vi, info.key, ctl.parent, ctl.key, isScan = false)
        }
      }

      res
    }

    private def removeNodeGUI(vp: VisualObj[S]): Unit = {
      _aggrTable.removeTuple(vp.aggr)
      _g.removeNode(vp.pNode)
      vp.inputs.foreach { case (_, vs) =>
        _g.removeNode(vs.pNode)
      }
      vp.outputs.foreach { case (_, vs) =>
        _g.removeNode(vs.pNode)
      }
      vp.params.foreach { case (_, vc) =>
        _g.removeNode(vc.pNode)
      }
    }

    private def initNodeGUI(obj: VisualObj[S], vn: VisualNode[S], locO: Option[Point2D]): VisualItem = {
      val pNode = vn.pNode
      val _vi   = _vis.getVisualItem(GROUP_GRAPH, pNode)
      val same  = vn == obj
      locO.fold {
        if (!same) {
          val _vi1 = _vis.getVisualItem(GROUP_GRAPH, obj.pNode)
          _vi.setEndX(_vi1.getX)
          _vi.setEndY(_vi1.getY)
        }
      } { loc =>
        _vi.setEndX(loc.getX)
        _vi.setEndY(loc.getY)
      }
      _vi
    }

    private def addNodeGUI(vp: VisualObj[S], links: List[VisualLink[S]], locO: Option[Point2D]): Unit = {
      initNodeGUI(vp, vp, locO)

      vp.inputs.foreach { case (_, vScan) =>
        initNodeGUI(vp, vScan, locO)
      }
      vp.outputs.foreach { case (_, vScan) =>
        initNodeGUI(vp, vScan, locO)
      }

      vp.params.foreach { case (_, vParam) =>
        initNodeGUI(vp, vParam, locO)
      }

      links.foreach { link =>
        link.source.outputs.get(link.sourceKey).foreach { sourceVisScan =>
          if (link.isScan)
            link.sink.inputs.get(link.sinkKey).foreach { sinkVisScan =>
              addScanScanEdgeGUI(sourceVisScan, sinkVisScan)
            }
          else
            link.sink.params.get(link.sinkKey).foreach { sinkVisCtl =>
              addScanControlEdgeGUI(sourceVisScan, sinkVisCtl)
            }
        }
      }
    }

    private def addControlGUI(vp: VisualObj[S], vc: VisualControl[S] /* , locO: Option[Point2D] */): Unit = {
      initNodeGUI(vp, vc, None /* locO */)
      val old = vp.params.get(vc.key)
      vp.params += vc.key -> vc
      for {
        m    <- vc.mapping
        vSrc <- m.source
      } {
        /* val pEdge = */ _g.addEdge(vSrc.pNode, vc.pNode)
        vSrc.mappings += vc
        // m.pEdge = Some(pEdge)
      }
      old.foreach(removeControlGUI(vp, _))
    }

    def removeControlGUI(visObj: VisualObj[S], vc: VisualControl[S]): Unit = {
      val key = vc.key
      visObj.params -= key
      val _vi = _vis.getVisualItem(GROUP_GRAPH, vc.pNode)
      visObj.aggr.removeItem(_vi)
      // val loc = new Point2D.Double(_vi.getX, _vi.getY)
      TxnExecutor.defaultAtomic { implicit itx =>
        // println(s"setLocationHint($visObj -> $key, $loc)")
        // setLocationHint(visObj -> key, loc)
        vc.mapping.foreach { m =>
          m.synth.swap(None).foreach { synth =>
            implicit val tx = Txn.wrap(itx)
            synth.dispose()
          }
        }
      }
      _g.removeNode(vc.pNode)
    }

    def addScanScanEdgeGUI(source: VisualScan[S], sink: VisualScan[S]): Unit = {
      val pEdge = _g.addEdge(source.pNode, sink.pNode)
      source.sinks   += pEdge
      sink  .sources += pEdge
    }

    def addScanControlEdgeGUI(source: VisualScan[S], sink: VisualControl[S]): Unit = {
      /* val pEdge = */ _g.addEdge(source.pNode, sink.pNode)
      source.mappings += sink
      // sink  .mapping.foreach { m => ... }
    }

    def removeEdgeGUI(source: VisualScan[S], sink: VisualScan[S]): Unit =
      source.sinks.find(_.getTargetNode == sink.pNode).foreach { pEdge =>
        source.sinks   -= pEdge
        sink  .sources -= pEdge
        _g.removeEdge(pEdge)
      }

    private val soloVolume  = Ref(soloAmpSpec._2)  // 0.5

    private val soloInfo    = Ref(Option.empty[(VisualObj[S], Synth)])

    private def getAuralScanData(aural: AuralObj[S], key: String = Proc.Obj.scanMainOut)
                                (implicit tx: S#Tx): Option[(AudioBus, Node)] = aural match {
      case ap: AuralObj.Proc[S] =>
        val d = ap.data
        for {
          either  <- d.getScanOut(key)
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
          (bus, node) <- getAuralScanData(auralProc)
        } {
          val sg = SynthGraph {
            import de.sciss.synth._
            import de.sciss.synth.ugen._
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
      _masterSynth.get(tx.peer).foreach(_.set("amp" -> v))

    //    masterProc.foreach { pMaster =>
    //      // pMaster.control("amp").v = v
    //    }

    def setSoloVolume(v: Double)(implicit tx: S#Tx): Unit = {
      implicit val itx = tx.peer
      val oldV = soloVolume.swap(v)
      if (v == oldV) return
      soloInfo().foreach(_._2.set("amp" -> v))
    }

    // private def close(p: Container): Unit = p.peer.getParent.remove(p.peer)

    def saveMacro(name: String, sel: Set[VisualObj[S]]): Unit =
      cursor.step { implicit tx =>
       val copies = Nuages.copyGraph(sel.map(_.objH())(breakOut))

        val macroF = Folder[S]
        copies.foreach(macroF.addLast)
        val nuagesF = panel.nuages.folder
        val parent = nuagesF.iterator.collect {
          case FolderElem.Obj(parentObj) if parentObj.name == Nuages.NameMacros => parentObj
        } .toList.headOption.getOrElse {
          val res = Folder[S]
          val resObj = Obj(FolderElem(res))
          resObj.name = Nuages.NameMacros
          nuagesF.addLast(resObj)
          res
        }

        val macroFObj = Obj(FolderElem(macroF))
        macroFObj.name = name
        parent.addLast(macroFObj)
      }

    private lazy val createGenDialog: OverlayPanel = {
      val p = new OverlayPanel()
      p.contents += listGen.component
      p.contents += Swing.VStrut(4)
      p.contents += listCol1.component
      p.contents += Swing.VStrut(4)
      p.onComplete {
        listGen.guiSelection match {
          case Vec(genIdx) =>
            val colIdxOpt = listCol1.guiSelection.headOption
            p.close()
            val displayPt = display.getAbsoluteCoordinate(p.location, null)
            atomic { implicit tx =>
              // val nuages = nuagesH()
              for {
                genList <- listGen.list
                gen <- genList.get(genIdx) // nuages.generators.get(genIdx)
              } {
                // val colOpt = colIdxOpt.flatMap(nuages.collectors.get)
                val colOpt = for {
                  colIdx  <- colIdxOpt
                  colList <- listCol1.list
                  col     <- colList.get(colIdx)
                } yield col

                createGenerator(gen, colOpt, displayPt)
              }
            }
          case _ =>
        }
      }
    }

    private lazy val createInsertMacroDialog: OverlayPanel = {
      val p = new OverlayPanel()
      p.contents += listMacro.component
      p.contents += Swing.VStrut(4)
      p.onComplete {
        listMacro.guiSelection match {
          case Vec(macIdx) =>
            p.close()
            val displayPt = display.getAbsoluteCoordinate(p.location, null)
            atomic { implicit tx =>
              for {
                macroList <- listMacro.list
                FolderElem.Obj(macroFObj) <- macroList.get(macIdx)
              } {
                insertMacro(macroFObj, displayPt)
              }
            }
          case _ =>
        }
      }
    }

    private var fltPred: VisualScan[S] = _
    private var fltSucc: VisualScan[S] = _

    private lazy val createFilterInsertDialog = {
      val p = new OverlayPanel()
      p.contents += listFlt1.component
      p.contents += Swing.VStrut(4)
      p.onComplete {
        listFlt1.guiSelection match {
          case Vec(fltIdx) =>
          p.close()
          val displayPt = display.getAbsoluteCoordinate(p.location, null)
          atomic { implicit tx =>
            // val nuages = nuagesH()
            for {
              fltList       <- listFlt1.list
              Proc.Obj(flt) <- fltList.get(fltIdx) // nuages.filters.get(fltIdx)
            } {
              (fltPred.parent.objH(), fltSucc.parent.objH()) match {
                case (Proc.Obj(pred), Proc.Obj(succ)) =>
                  for {
                    predScan <- pred.outputs.get(fltPred.key)
                    succScan <- succ.inputs .get(fltSucc.key)
                  } {
                    insertFilter(predScan, succScan, flt, displayPt)
                  }
                case _ =>
              }
            }
          }
        }
      }
    }

    private def createFilterOnlyFromDialog(p: OverlayPanel)(objFun: S#Tx => Option[Obj[S]]): Unit = {
      p.close()
      val displayPt = display.getAbsoluteCoordinate(p.location, null)
      cursor.step { implicit tx =>
        for {
          Proc.Obj(flt) <- objFun(tx)
        } {
          fltPred.parent.objH() match {
            case Proc.Obj(pred) =>
              for {
                predScan <- pred.outputs.get(fltPred.key)
              } {
                appendFilter(predScan, flt, None, displayPt)
              }
            case _ =>
          }
        }
      }
    }

    private lazy val createFilterAppendDialog = {
      val p = new OverlayPanel()
      p.contents += listFlt2.component
      p.contents += Swing.VStrut(4)
      p.contents += listCol2.component
      p.contents += Swing.VStrut(4)
      p.onComplete {
        (listFlt2.guiSelection.headOption, listCol2.guiSelection.headOption) match {
          case (Some(fltIdx), None) =>
            createFilterOnlyFromDialog(p) { implicit tx =>
              for {
                fltList <- listFlt2.list
                flt     <- fltList.get(fltIdx)
              } yield flt
              // nuages.filters.get(fltIdx)
            }

          case (None, Some(colIdx)) =>
            createFilterOnlyFromDialog(p) { implicit tx =>
              for {
                colList <- listCol2.list
                col     <- colList.get(colIdx)
              } yield col
              // nuages.collectors.get(colIdx)
            }

          case (Some(fltIdx), Some(colIdx)) =>
            p.close()
            val displayPt = display.getAbsoluteCoordinate(p.location, null)
            atomic { implicit tx =>
              for {
                fltList       <- listFlt2.list
                colList       <- listCol2.list
                Proc.Obj(flt) <- fltList.get(fltIdx)
                Proc.Obj(col) <- colList.get(colIdx)
                // Proc.Obj(flt) <- nuages.filters   .get(fltIdx)
                // Proc.Obj(col) <- nuages.collectors.get(colIdx)
              } {
                fltPred.parent.objH() match {
                  case Proc.Obj(pred) =>
                    for {
                      predScan <- pred.outputs.get(fltPred.key)
                    } {
                      appendFilter(predScan, flt, Some(col), displayPt)
                    }
                  case _ =>
                }
              }
            }

          case _ =>
        }
      }
    }

    private def exec(obj: Obj[S], key: String)(implicit tx: S#Tx): Unit =
      for (Action.Obj(self) <- obj.attr.get(key))
        self.execute(Action.Universe(self, workspace, invoker = Some(obj)))

    private def prepareObj(obj: Obj[S])(implicit tx: S#Tx): Unit = exec(obj, "nuages-prepare")
    private def disposeObj(obj: Obj[S])(implicit tx: S#Tx): Unit = exec(obj, "nuages-dispose")

    private def finalizeProcAndCollector(proc: Obj[S], colSrcOpt: Option[Obj[S]], pt: Point2D)
                                        (implicit tx: S#Tx): Unit =
      for (tl <- nuages.timeline.modifiableOption) {
        val colOpt = colSrcOpt.map(Obj.copy[S])

        (proc, colOpt) match {
          case (Proc.Obj(genP), Some(Proc.Obj(colP))) =>
            val procGen = genP
            val procCol = colP
            val scanOut = procGen.outputs.add(Proc.Obj.scanMainOut)
            val scanIn  = procCol.inputs .add(Proc.Obj.scanMainIn )
            scanOut.add(scanIn)

          case _ =>
        }

        prepareObj(proc)
        colOpt.foreach(prepareObj)

        setLocationHint(proc, if (colOpt.isEmpty) pt else new Point2D.Double(pt.getX, pt.getY - 30))
        colOpt.foreach(setLocationHint(_, new Point2D.Double(pt.getX, pt.getY + 30)))

        addToTimeline(tl, proc)
        colOpt.foreach(addToTimeline(tl, _))
      }

    private def insertMacro(macroF: Folder[S], pt: Point2D)(implicit tx: S#Tx): Unit = {
      val copies = Nuages.copyGraph(macroF.iterator.toIndexedSeq)
      copies.foreach { cpy =>
        finalizeProcAndCollector(cpy, None, pt)
      }
    }

    def createGenerator(genSrc: Obj[S], colSrcOpt: Option[Obj[S]], pt: Point2D)(implicit tx: S#Tx): Unit = {
      val gen = Obj.copy(genSrc)
      finalizeProcAndCollector(gen, colSrcOpt, pt)
    }

    private def addToTimeline(tl: Timeline.Modifiable[S], obj: Obj[S])(implicit tx: S#Tx): Unit = {
      val imp = ExprImplicits[S]
      val time    = transport.position
      val span    = Span.From(time)
      val spanEx  = SpanLikeEx.newVar(span)
      tl.add(spanEx, obj)
    }

    def insertFilter(pred: Scan[S], succ: Scan[S], fltSrc: Obj[S], fltPt: Point2D)(implicit tx: S#Tx): Unit = {
      val flt = Obj.copy(fltSrc)

      flt match {
        case Proc.Obj(fltP) =>
          val procFlt  = fltP
          pred.add(procFlt.inputs.add(Proc.Obj.scanMainIn))
          // we may handle 'sinks' here by ignoring them when they don't have an `"out"` scan.
          for {
            fltOut <- procFlt.outputs.get(Proc.Obj.scanMainOut)
          } {
            pred  .remove(succ)
            fltOut.add   (succ)
          }

        case _ =>
      }

      finalizeProcAndCollector(flt, None, fltPt)
    }

    def appendFilter(pred: Scan[S], fltSrc: Obj[S], colSrcOpt: Option[Obj[S]], fltPt: Point2D)
                    (implicit tx: S#Tx): Unit = {
      val flt = Obj.copy(fltSrc)

      flt match {
        case Proc.Obj(fltP) =>
          val procFlt  = fltP
          pred.add(procFlt.inputs.add(Proc.Obj.scanMainIn))
        case _ =>
      }

      finalizeProcAndCollector(flt, colSrcOpt, fltPt)
    }

    def showCreateGenDialog(pt: Point): Boolean = {
      requireEDT()
      showOverlayPanel(createGenDialog, Some(pt))
    }

    def showInsertFilterDialog(pred: VisualScan[S], succ: VisualScan[S], pt: Point): Boolean = {
      requireEDT()
      fltPred = pred
      fltSucc = succ
      showOverlayPanel(createFilterInsertDialog, Some(pt))
    }

    def showInsertMacroDialog(): Boolean = {
      requireEDT()
      showOverlayPanel(createInsertMacroDialog)
    }

    def showAppendFilterDialog(pred: VisualScan[S], pt: Point): Boolean = {
      requireEDT()
      fltPred = pred
      showOverlayPanel(createFilterAppendDialog, Some(pt))
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
