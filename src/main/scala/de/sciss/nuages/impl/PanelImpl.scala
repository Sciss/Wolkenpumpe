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

import de.sciss.lucre.expr.{SpanLikeObj, DoubleObj}
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Obj, Disposable, TxnLike}
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.swing.{ListView, defer, deferTx, requireEDT}
import de.sciss.lucre.synth.{AudioBus, Node, Synth, Sys, Txn}
import de.sciss.span.{Span, SpanLike}
import de.sciss.synth.proc.{Action, AuralObj, AuralSystem, Folder, Proc, Scan, Timeline, Transport, WorkspaceHandle}
import de.sciss.synth.{proc, SynthGraph, addToTail, message}
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

    new PanelImpl[S](nuagesH, nodeMap, scanMap, missingScans, config, transport, aural,
                listGen = listGen, listFlt1 = listFlt1, listCol1 = listCol1, listFlt2 = listFlt2, listCol2 = listCol2,
                listMacro = listMacro)
      .init(timelineObj)
  }

  final val GROUP_NODES   = "graph.nodes"
  final val GROUP_EDGES   = "graph.edges"

  final val AGGR_PROC     = "aggr"

  final val ACTION_LAYOUT = "layout"
  final val ACTION_COLOR  = "color"
  final val LAYOUT_TIME   = 50

  def mkListView[S <: Sys[S]](folderOpt: Option[Folder[S]])
                             (implicit tx: S#Tx, cursor: stm.Cursor[S]): ListView[S, Obj[S], Unit] = {
    import proc.Implicits._
    val h = ListView.Handler[S, Obj[S], Unit /* Obj.Update[S] */] { implicit tx => obj => obj.name } (_ => (_, _) => None)
    implicit val ser = de.sciss.lucre.expr.List.serializer[S, Obj[S] /* , Unit */ /* Obj.Update[S] */]
    // val res = ListView[S, Obj[S], Unit /* Obj.Update[S] */, String](folder, h)
    val res = ListView.empty[S, Obj[S], Unit /* Obj.Update[S] */, String](h)
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

}

// nodeMap: uses timed-id as key
final class PanelImpl[S <: Sys[S]](nuagesH: stm.Source[S#Tx, Nuages[S]],
                                   protected val nodeMap: stm.IdentifierMap[S#ID, S#Tx, VisualObj[S]],
                                   scanMap: stm.IdentifierMap[S#ID, S#Tx, PanelImpl.ScanInfo[S]],
                                   missingScans: stm.IdentifierMap[S#ID, S#Tx, List[VisualControl[S]]],
                                   val config   : Nuages.Config,
                                   val transport: Transport[S],
                                   val aural    : AuralSystem,
                                   protected val listGen  : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
                                   protected val listFlt1 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
                                   protected val listCol1 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
                                   protected val listFlt2 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
                                   protected val listCol2 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
                                   protected val listMacro: ListView[S, Obj[S], Unit /* Obj.Update[S] */])
                                 (implicit val cursor: stm.Cursor[S], protected val workspace: WorkspaceHandle[S])
  extends NuagesPanel[S]
  // here comes your cake!
  with PanelImplInit   [S]
  with PanelImplDialogs[S]
  with PanelImplTxnFuns[S]
  with PanelImplGuiInit[S]
  with PanelImplGuiFuns[S] {
  panel =>

  import cursor.{step => atomic}
  import de.sciss.nuages.NuagesPanel._
  import PanelImpl._

  protected def main: NuagesPanel[S] = this

  protected var timelineObserver : Disposable[S#Tx] = _
  protected var transportObserver: Disposable[S#Tx] = _
  protected val auralObserver = Ref(Option.empty[Disposable[S#Tx]])
  protected val auralTimeline = Ref(Option.empty[AuralObj.Timeline[S]])

  private val auralToViewMap  = TMap.empty[AuralObj[S], VisualObj[S]]
  private val viewToAuralMap  = TMap.empty[VisualObj[S], AuralObj[S]]

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
    nodeMap       .dispose()
    scanMap       .dispose()
    missingScans  .dispose()

    keyControl    .dispose()
  }

  protected def disposeAuralObserver()(implicit tx: S#Tx): Unit = {
    auralTimeline.set (None)(tx.peer)
    auralObserver.swap(None)(tx.peer).foreach(_.dispose())
  }

  def selection: Set[VisualNode[S]] = {
    requireEDT()
    val selectedItems = visualization.getGroup(GROUP_SELECTION)
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
    def exec(): Unit = visualization.synchronized {
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
    visualization.cancel(ACTION_COLOR)
    visualization.cancel(ACTION_LAYOUT)
  }

  @inline private def startAnimation(): Unit =
    visualization.run(ACTION_COLOR)

  //    private def visDo[A](code: => A): A =
  //      visualization.synchronized {
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
    val locO  = removeLocationHint(obj) // locHintMap.getAndTransform(_ - obj)(tx.peer).get(obj)

    var links: List[VisualLink[S]] = obj match {
      case objT: Proc[S] =>
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
  protected def auralObjAdded(vp: VisualObj[S], aural: AuralObj[S])(implicit tx: S#Tx): Unit = {
    if (config.meters) getAuralScanData(aural).foreach { case (bus, node) =>
      val meterSynth = mkMeter(bus, node)(vp.meterUpdate)
      vp.meterSynth = Some(meterSynth)
    }
    auralToViewMap.put(aural, vp)(tx.peer)
    viewToAuralMap.put(vp, aural)(tx.peer)
  }

  protected def auralObjRemoved(aural: AuralObj[S])(implicit tx: S#Tx): Unit = {
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
        case objT: Proc[S] =>
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
    aggrTable.removeTuple(vp.aggr)
    graph.removeNode(vp.pNode)
    vp.inputs.foreach { case (_, vs) =>
      graph.removeNode(vs.pNode)
    }
    vp.outputs.foreach { case (_, vs) =>
      graph.removeNode(vs.pNode)
    }
    vp.params.foreach { case (_, vc) =>
      graph.removeNode(vc.pNode)
    }
  }

  private def initNodeGUI(obj: VisualObj[S], vn: VisualNode[S], locO: Option[Point2D]): VisualItem = {
    val pNode = vn.pNode
    val _vi   = visualization.getVisualItem(GROUP_GRAPH, pNode)
    val same  = vn == obj
    locO.fold {
      if (!same) {
        val _vi1 = visualization.getVisualItem(GROUP_GRAPH, obj.pNode)
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
      /* val pEdge = */ graph.addEdge(vSrc.pNode, vc.pNode)
      vSrc.mappings += vc
      // m.pEdge = Some(pEdge)
    }
    old.foreach(removeControlGUI(vp, _))
  }

  def removeControlGUI(visObj: VisualObj[S], vc: VisualControl[S]): Unit = {
    val key = vc.key
    visObj.params -= key
    val _vi = visualization.getVisualItem(GROUP_GRAPH, vc.pNode)
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
    graph.removeNode(vc.pNode)
  }

  def addScanScanEdgeGUI(source: VisualScan[S], sink: VisualScan[S]): Unit = {
    val pEdge = graph.addEdge(source.pNode, sink.pNode)
    source.sinks   += pEdge
    sink  .sources += pEdge
  }

  def addScanControlEdgeGUI(source: VisualScan[S], sink: VisualControl[S]): Unit = {
    /* val pEdge = */ graph.addEdge(source.pNode, sink.pNode)
    source.mappings += sink
    // sink  .mapping.foreach { m => ... }
  }

  def removeEdgeGUI(source: VisualScan[S], sink: VisualScan[S]): Unit =
    source.sinks.find(_.getTargetNode == sink.pNode).foreach { pEdge =>
      source.sinks   -= pEdge
      sink  .sources -= pEdge
      graph.removeEdge(pEdge)
    }

  private val soloVolume  = Ref(soloAmpSpec._2)  // 0.5

  private val soloInfo    = Ref(Option.empty[(VisualObj[S], Synth)])

  private def getAuralScanData(aural: AuralObj[S], key: String = Proc.scanMainOut)
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
      import proc.Implicits._
      val parent = nuagesF.iterator.collect {
        case parentObj: Folder[S] if parentObj.name == Nuages.NameMacros => parentObj
      } .toList.headOption.getOrElse {
        val res = Folder[S]
        res.name = Nuages.NameMacros
        nuagesF.addLast(res)
        res
      }

      macroF.name = name
      parent.addLast(macroF)
    }
}