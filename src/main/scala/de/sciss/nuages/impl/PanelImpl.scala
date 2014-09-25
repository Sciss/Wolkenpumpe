package de.sciss.nuages
package impl

import java.awt.{Dimension, Rectangle, LayoutManager, Point, EventQueue, Color}
import java.awt.geom.Point2D
import javax.swing.event.{AncestorEvent, AncestorListener}
import javax.swing.JPanel

import de.sciss.lucre.stm
import de.sciss.lucre.swing.{deferTx, requireEDT}
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.synth.Sys
import de.sciss.swingplus.ListView
import de.sciss.synth.ugen.Out
import de.sciss.synth.{GE, AudioBus}
import de.sciss.synth.proc.Obj
import prefuse.action.{RepaintAction, ActionList}
import prefuse.action.assignment.ColorAction
import prefuse.action.layout.graph.ForceDirectedLayout
import prefuse.activity.Activity
import prefuse.controls.{PanControl, WheelZoomControl, ZoomControl}
import prefuse.data.Graph
import prefuse.render.{DefaultRendererFactory, PolygonRenderer, EdgeRenderer}
import prefuse.util.ColorLib
import prefuse.visual.{AggregateTable, VisualGraph, VisualItem}
import prefuse.visual.expression.InGroupPredicate
import prefuse.{Constants, Display, Visualization}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.{Ref, TxnLocal}
import scala.swing.{Container, Orientation, SequentialContainer, BoxPanel, Panel, Swing, Component}

object PanelImpl {
  def apply[S <: Sys[S]](nuages: Nuages[S], config: Nuages.Config)
                        (implicit tx: S#Tx, cursor: stm.Cursor[S]): NuagesPanel[S] = {
    val nuagesH = tx.newHandle(nuages)
    val res     = new Impl[S](nuagesH, config)
    deferTx(res.guiInit())
    res
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

  private final class Impl[S <: Sys[S]](nuagesH: stm.Source[S#Tx, Nuages[S]], val config: Nuages.Config)
                                       (implicit cursor: stm.Cursor[S])
    extends NuagesPanel[S] with ComponentHolder[Component] {
    panel =>

    import NuagesPanel._
    import cursor.{step => atomic}

    //   import ProcWorld._
    //   import Proc._

    private var _vis: Visualization = _
    //  val world   = ProcDemiurg.worlds(config.server)
    private var _disp: Display = _

    //  private var soloFactory: Option[ProcFactory] = None

    def display: Display = _disp
    def visualization: Visualization = _vis

    private var g: Graph = _
    private var vg: VisualGraph = _

    private val actions = new NuagesActions(this)

    private var aggrTable: AggregateTable = _

    //  /* private */ var procMap   = Map.empty[Proc, VisualProc]
    //  /* private */ var edgeMap   = Map.empty[ProcEdge, Edge]
    //  /* private */ var meterMap  = Map.empty[Proc, VisualProc]

    //  var transition: Double => Transition = (_) => Instant

    //  private var meterFactory : Option[ProcFactory]  = None
    //  private var masterFactory: Option[ProcFactory]  = None
    //  private var masterProc   : Option[Proc]         = None
    private var masterBusVar : Option[AudioBus]     = None

    def masterBus: Option[AudioBus] = masterBusVar

    //  private object topoListener extends ProcWorld.Listener {
    //    def updated(u: ProcWorld.Update): Unit = topoUpdate(u)
    //  }
    //
    //  private object procListener extends Proc.Listener {
    //    def updated(u: Proc.Update): Unit =
    //      defer(procUpdate(u))
    //  }

    // ---- ProcFactoryProvider ----
    //  val factoryManager      = new ProcFactoryViewManager(config)
    //  var genFactory          = Option.empty[ProcFactory]
    //  var filterFactory       = Option.empty[ProcFactory]
    //  var diffFactory         = Option.empty[ProcFactory]
    //  var collector           = Option.empty[Proc]

    private var locHintMap  = TxnLocal(Map.empty[Obj[S], Point2D])

    def setLocationHint(p: Obj[S], loc: Point2D)(implicit tx: S#Tx): Unit =
      locHintMap.transform(_ + (p -> loc))(tx.peer)

    private var overlay = Option.empty[Component]

    def nuages(implicit tx: S#Tx): Nuages[S] = nuagesH()

    def dispose()(implicit tx: S#Tx): Unit = ()

    // ---- constructor ----
    def guiInit(): Unit = {
      _vis  = new Visualization
      _disp = new Display(_vis)

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
      _disp.setSize(800, 600)
      _disp.addControlListener(new ZoomControl())
      _disp.addControlListener(new WheelZoomControl())
      _disp.addControlListener(new PanControl())
      _disp.addControlListener(new DragControl(_vis))
      _disp.addControlListener(new ClickControl(this))
      _disp.addControlListener(new ConnectControl(_vis))
      _disp.setHighQuality(true)

      // ------------------------------------------------

      edgeRenderer.setHorizontalAlignment1(Constants.CENTER)
      edgeRenderer.setHorizontalAlignment2(Constants.CENTER)
      edgeRenderer.setVerticalAlignment1(Constants.CENTER)
      edgeRenderer.setVerticalAlignment2(Constants.CENTER)

      _disp.setForeground(Color.WHITE)
      _disp.setBackground(Color.BLACK)

      //      setLayout( new BorderLayout() )
      //      add( display, BorderLayout.CENTER )
      val p = new JPanel
      p.setLayout(new Layout(_disp))
      p.add(_disp)

      _vis.run(ACTION_COLOR)

      component = Component.wrap(p)
    }

    private def init()(implicit tx: S#Tx): Unit = {
      //      // import DSL._
      //      import synth._
      //      import ugen._
      //
      //      def meterGraph(sig: In): Unit = {
      //        val meterTr = Impulse.kr(1000.0 / LAYOUT_TIME)
      //        val peak = Peak.kr(sig, meterTr) // .outputs
      //        //            val peakM      = peak.tail.foldLeft[ GE ]( peak.head )( _ max _ ) \ 0
      //        val peakM = Reduce.max(peak)
      //        val me = Proc.local
      //        // warning: currently a bug in SendReply? if values are audio-rate,
      //        // trigger needs to be audio-rate, too
      //        meterTr.react(peakM /* :: rms :: Nil */) { vals =>
      //          defer(meterMap.get(me).foreach { visOut =>
      //            visOut.meterUpdate(vals(0).toFloat)
      //          })
      //        }
      //      }
      //
      //      if (config.meters) meterFactory = Some(diff("$meter") {
      //        graph { sig: In =>
      //          meterGraph(sig)
      //          0.0
      //        }
      //      })
      //
      //      if (config.collector) {
      //        if (verbose) Console.print("Creating collector...")
      //        val p = (filter("_+")(graph((x: In) => x))).make
      //        collector = Some(p)
      //        if (verbose) println(" ok")
      //      }
      //
      //      config.masterChannels.foreach { chans =>
      //        if (verbose) Console.print("Creating master...")
      //        val name = if (config.collector) "_master" else "$master"
      //        val pMaster = (diff(name) {
      //          val pAmp = pControl("amp", masterAmpSpec._1, masterAmpSpec._2)
      //          /* val pIn = */ pAudioIn("in", None)
      //          graph { (sig: In) => efficientOuts(Limiter.ar(sig * pAmp.kr, (-0.2).dbamp), chans); 0.0}
      //        }).make
      //        pMaster.control("amp").v = masterAmpSpec._2
      //        // XXX bus should be freed in panel disposal
      //
      //        val b = Bus.audio(config.server, chans.size)
      //        collector match {
      //          case Some(pColl) =>
      //            val b2 = Bus.audio(config.server, chans.size)
      //            pColl.audioInput("in").bus = Some(RichBus.wrap(b2))
      //            pColl ~> pMaster
      //            pColl.play
      //          case None =>
      //        }
      //        pMaster.audioInput("in").bus = Some(RichBus.wrap(b))
      //
      //        val g = RichGroup(Group(config.server))
      //        g.play(RichGroup.default(config.server), addToTail)
      //        pMaster.group = g
      //        pMaster.play
      //        masterProc = Some(pMaster)
      //        masterBusVar = Some(b)
      //
      //        masterFactory = Some(diff("$diff") {
      //          graph { (sig: In) =>
      //            //                  require( sig.numOutputs == b.numChannels )
      //            if (sig.numChannels != b.numChannels) println("Warning - masterFactory. sig has " + sig.numChannels + " outputs, but bus has " + b.numChannels + " channels ")
      //            if (config.meters) meterGraph(sig)
      //            Out.ar(b.index, sig)
      //          }
      //        })
      //
      //        factoryManager.startListening
      //        if (verbose) println(" ok")
      //      }
      //
      //      soloFactory = config.soloChannels.map { chans => diff("$solo") {
      //        val pAmp = pControl("amp", soloAmpSpec._1, soloAmpSpec._2)
      //
      //        graph { sig: In =>
      //          //               val numIn   = sig.numOutputs
      //          val numOut = chans.size
      //          //               val sigOut  = Array.fill[ GE ]( numOut )( 0.0f )
      //          //               val sca     = (numOut - 1).toFloat / (numIn - 1)
      //
      //          val sigOut = SplayAz.ar(numOut, sig) // , spread, center, level, width, orient
      //
      //          //               sig.outputs.zipWithIndex.foreach { tup =>
      //          //                  val (sigIn, inCh) = tup
      //          //                  val outCh         = inCh * sca
      //          //                  val fr            = outCh % 1f
      //          //                  val outChI        = outCh.toInt
      //          //                  if( fr == 0f ) {
      //          //                     sigOut( outChI ) += sigIn
      //          //                  } else {
      //          //                     sigOut( outChI )     += sigIn * (1 - fr).sqrt
      //          //                     sigOut( outChI + 1 ) += sigIn * fr.sqrt
      //          //                  }
      //          //               }
      //          efficientOuts(sigOut /*.toSeq */ * pAmp.kr, chans)
      //          0.0
      //        }
      //      }
      //      }
      //
      //      world.addListener(topoListener)
      //    }
    }

    private def efficientOuts(sig: GE, chans: Vec[Int]): Unit = {
      //      require( sig.numOutputs == chans.size, sig.numOutputs.toString + " =! " + chans.size )
      //if( sig.numOutputs != chans.size ) println( "WARNING: efficientOuts. sig has " + sig.numOutputs.toString + " outs, while chans has = " + chans.size )
      if (chans.isEmpty) return

      require(chans == Vec.tabulate(chans.size)(i => i + chans(0)))
      Out.ar(chans(0), sig)

      //      import synth._
      //      import ugen._
      //
      //      def funky( seq: Vec[ (Int, UGenIn) ]) {
      //         seq.headOption.foreach { tup =>
      //            val (idx, e1)  = tup
      //            var cnt = -1
      //            val (ja, nein) = seq.span { tup =>
      //               val (idx2, _) = tup
      //               cnt += 1
      //               idx2 == idx + cnt
      //            }
      //            Out.ar( idx, ja.map( _._2 ) : GE )
      //            funky( nein )
      //         }
      //      }
      //
      //      funky( chans.zip( sig.outputs ).sortBy( _._1 ))
    }

    private def defer(code: => Unit): Unit =
      EventQueue.invokeLater(new Runnable {
        def run(): Unit = code
      })

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

    //  private def topAddProc(p: Proc, pMeter: Option[Proc]): Unit = {
    //    val pNode = g.addNode()
    //    val vi    = vis.getVisualItem(GROUP_GRAPH, pNode)
    //    val locO  = locHintMap.get(p)
    //    locO.foreach { loc =>
    //      locHintMap -= p
    //      vi.setEndX(loc.getX)
    //      vi.setEndY(loc.getY)
    //    }
    //    val aggr = aggrTable.addItem().asInstanceOf[AggregateItem]
    //    //println( "ADD AGGR " + aggr )
    //    aggr.addItem(vi)
    //
    //    def createNode = {
    //      val pParamNode = g.addNode()
    //      val pParamEdge = g.addEdge(pNode, pParamNode)
    //      val vi = vis.getVisualItem(GROUP_GRAPH, pParamNode)
    //      locO.foreach { loc =>
    //        vi.setEndX(loc.getX)
    //        vi.setEndY(loc.getY)
    //      }
    //      aggr.addItem(vi)
    //      (pParamNode, pParamEdge, vi)
    //    }
    //
    //    lazy val vProc: VisualProc = {
    //      val vParams: Map[String, VisualParam] = p.params.collect {
    //        case pFloat: ProcParamFloat =>
    //          val (pParamNode, pParamEdge, vi) = createNode
    //          val pControl = p.control(pFloat.name)
    //          val vControl = VisualControl(pControl, pParamNode, pParamEdge)(vProc)
    //          vi.set(COL_NUAGES, vControl)
    //          vControl.name -> vControl
    //
    //        case pParamBus: ProcParamAudioInput =>
    //          val (pParamNode, pParamEdge, vi) = createNode
    //          vi.set(VisualItem.SIZE, 0.33333f)
    //          val pBus = p.audioInput(pParamBus.name)
    //          val vBus = VisualAudioInput(panel, pBus, pParamNode, pParamEdge)
    //          vi.set(COL_NUAGES, vBus)
    //          vBus.name -> vBus
    //
    //        case pParamBus: ProcParamAudioOutput =>
    //          val (pParamNode, pParamEdge, vi) = createNode
    //          vi.set(VisualItem.SIZE, 0.33333f)
    //          val pBus = p.audioOutput(pParamBus.name)
    //          val vBus = VisualAudioOutput(panel, pBus, pParamNode, pParamEdge)
    //          vi.set(COL_NUAGES, vBus)
    //          vBus.name -> vBus
    //
    //      } (breakOut)
    //
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

    def setSolo(vp: VisualProc[S], onOff: Boolean): Unit = {
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
      b.contents += BasicButton("Create")(createAction)
      b.contents += Swing.HStrut(8)
      b.contents += BasicButton("Abort")(close(p))
      p.contents += b
      // b
    }

    private def close(p: Container): Unit = p.peer.getParent.remove(p.peer)

    private lazy val createGenDialog: Panel = {
      val p = new OverlayPanel()
      val genView = new ListView[String](Nil)  // createFactoryView(ProcGen)
      p.contents += genView
      p.contents += Swing.VStrut(4)
      val diffView = new ListView[String](Nil) // createFactoryView(ProcDiff)
      p.contents += diffView
      p.contents += Swing.VStrut(4)
      createAbortBar(p) {
//        (genView.selection, diffView.selection) match {
//          case (Some(genF), Some(diffF)) =>
//            close(p)
//            val displayPt = display.getAbsoluteCoordinate(p.getLocation, null)
//            atomic { implicit tx =>
//              println("TODO: createProc")
//              // createProc(genF, diffF, displayPt)
//            }
//          case _ =>
//        }
      }
      pack(p)
    }

    def showCreateGenDialog(pt: Point): Boolean = {
      requireEDT()
      showOverlayPanel(createGenDialog, pt)
    }

    //  private def topAddEdges(edges: Set[ProcEdge]): Unit = {
    //    if (verbose) println("topAddEdges : " + edges)
    //    visDo(topAddEdgesI(edges))
    //  }
    //
    //  private def topAddEdgesI(edges: Set[ProcEdge]): Unit =
    //    edges.foreach { e =>
    //      procMap.get(e.sourceVertex).foreach { vProcSrc =>
    //        procMap.get(e.targetVertex).foreach { vProcTgt =>
    //          val outName = e.out.name
    //          val inName  = e.in .name
    //          vProcSrc.params.get(outName).map(_.pNode).foreach { pSrc =>
    //            vProcTgt.params.get(inName).map(_.pNode).foreach { pTgt =>
    //              val pEdge = g.addEdge(pSrc, pTgt)
    //              edgeMap += e -> pEdge
    //            }
    //          }
    //        }
    //      }
    //    }

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

    //   private def tryRepeatedly( code: => Unit ) {
    //      def iter( repeats: Int, print: Boolean ) {
    //         try {
    //            code
    //         } catch {
    //            case e =>
    //               if( print ) e.printStackTrace()
    //               if( repeats > 0 ) {
    //                  new SwingTimer( 1000, new ActionListener {
    //                     def actionPerformed( e: ActionEvent ) {
    //                        iter( repeats - 1, false )
    //                     }
    //                  }).start()
    //               }
    //         }
    //      }
    //      iter( 10, true )
    //   }

    private def topRemoveProc(vProc: VisualProc[S]): Unit = {
      // fucking prefuse -- always trouble with the aggregates
      //Thread.sleep(50)
      //println( "REMOVE AGGR " + vProc.aggr )
      //      vProc.aggr.removeAllItems()
      aggrTable.removeTuple(vProc.aggr)
      //      tryRepeatedly( aggrTable.removeTuple( vProc.aggr ))
      g.removeNode(vProc.pNode)
      vProc.params.values.foreach { vParam =>
        // WE MUST NOT REMOVE THE EDGES, AS THE REMOVAL OF
        // VPROC.PNODE INCLUDES ALL THE EDGES!
        //         g.removeEdge( vParam.pEdge )
        g.removeNode(vParam.pNode)
      }
      //    procMap -= vProc.proc
    }

    //  private def topRemoveEdges(edges: Set[ProcEdge]): Unit = {
    //    if (verbose) println("topRemoveEdges : " + edges)
    //    visDo {
    //      edges.foreach { e =>
    //        edgeMap.get(e).foreach { pEdge =>
    //          try {
    //            g.removeEdge(pEdge)
    //          }
    //          catch {
    //            case NonFatal(ex) => println("" + new java.util.Date() + " CAUGHT " + e + " : " + ex.getClass.getName)
    //          }
    //          edgeMap -= e
    //        }
    //      }
    //    }
    //  }

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
