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
import de.sciss.nuages.Nuages.Surface
import de.sciss.span.{Span, SpanLike}
import de.sciss.synth.proc.{Action, AuralObj, AuralSystem, Folder, Proc, Timeline, Transport, WorkspaceHandle}
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
                         workspace: WorkspaceHandle[S], context: NuagesContext[S]): NuagesPanel[S] = {
    val nuagesH       = tx.newHandle(nuages)

    val listGen       = mkListView(nuages.generators)
    val listFlt1      = mkListView(nuages.filters   )
    val listCol1      = mkListView(nuages.collectors)
    val listFlt2      = mkListView(nuages.filters   )
    val listCol2      = mkListView(nuages.collectors)
    val listMacro     = mkListView(nuages.macros    )

    val nodeMap       = tx.newInMemoryIDMap[NuagesObj[S]]
    // val scanMap       = tx.newInMemoryIDMap[NuagesOutput[S]] // ScanInfo [S]]
    val missingScans  = tx.newInMemoryIDMap[List[NuagesAttribute[S]]]
    val transport     = Transport[S](aural)
    val surface       = nuages.surface
    // transport.addObject(surface.peer)

    surface match {
      case Surface.Timeline(tl) =>
        new PanelImplTimeline[S](nuagesH, nodeMap, /* scanMap, */ missingScans, config, transport, aural,
          listGen = listGen, listFlt1 = listFlt1, listCol1 = listCol1, listFlt2 = listFlt2, listCol2 = listCol2,
          listMacro = listMacro)
          .init(tl)

      case Surface.Folder(f) =>
        new PanelImplFolder[S](nuagesH, nodeMap, /* scanMap, */ missingScans, config, transport, aural,
          listGen = listGen, listFlt1 = listFlt1, listCol1 = listCol1, listFlt2 = listFlt2, listCol2 = listCol2,
          listMacro = listMacro)
          .init(f)
    }
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
}

final class PanelImplTimeline[S <: Sys[S]](protected val nuagesH: stm.Source[S#Tx, Nuages[S]],
     val nodeMap: stm.IdentifierMap[S#ID, S#Tx, NuagesObj[S]],
//     protected val scanMap: stm.IdentifierMap[S#ID, S#Tx, NuagesOutput[S]],
     protected val missingScans: stm.IdentifierMap[S#ID, S#Tx, List[NuagesAttribute[S]]],
     val config   : Nuages.Config,
     val transport: Transport[S],
     val aural    : AuralSystem,
     protected val listGen  : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listFlt1 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listCol1 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listFlt2 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listCol2 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listMacro: ListView[S, Obj[S], Unit /* Obj.Update[S] */])
    (implicit val cursor: stm.Cursor[S],
     protected val workspace: WorkspaceHandle[S],
     val context: NuagesContext[S])
  extends PanelImpl[S, Timeline[S], AuralObj.Timeline[S]]
  with PanelImplTimelineInit[S]

final class PanelImplFolder[S <: Sys[S]](protected val nuagesH: stm.Source[S#Tx, Nuages[S]],
     val nodeMap: stm.IdentifierMap[S#ID, S#Tx, NuagesObj[S]],
//     protected val scanMap: stm.IdentifierMap[S#ID, S#Tx, NuagesOutput[S]],
     protected val missingScans: stm.IdentifierMap[S#ID, S#Tx, List[NuagesAttribute[S]]],
     val config   : Nuages.Config,
     val transport: Transport[S],
     val aural    : AuralSystem,
     protected val listGen  : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listFlt1 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listCol1 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listFlt2 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listCol2 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listMacro: ListView[S, Obj[S], Unit /* Obj.Update[S] */])
    (implicit val cursor: stm.Cursor[S],
     protected val workspace: WorkspaceHandle[S],
     val context: NuagesContext[S])
  extends PanelImpl[S, Folder[S], AuralObj.Folder[S]]
  with PanelImplFolderInit[S]

// nodeMap: uses timed-id as key
trait PanelImpl[S <: Sys[S], Repr <: Obj[S], AuralRepr <: AuralObj[S]]
  extends NuagesPanel[S]
  // here comes your cake!
  with PanelImplDialogs[S]
  with PanelImplTxnFuns[S]
  with PanelImplReact  [S]
  with PanelImplMixer  [S]
  with PanelImplGuiInit[S]
  // with PanelImplGuiFuns[S]
  {
  panel =>

  import NuagesPanel.{GROUP_SELECTION, GROUP_GRAPH, COL_NUAGES}
  import PanelImpl._
  import TxnLike.peer

  // ---- abstract ----

  protected def nuagesH: stm.Source[S#Tx, Nuages[S]]

  protected def auralReprRef: Ref[Option[AuralRepr]]

  protected def disposeTransport()(implicit tx: S#Tx): Unit

  // ---- impl ----

  protected def main: NuagesPanel[S] = this

  protected var observers     = List.empty[Disposable[S#Tx]]
  protected val auralObserver = Ref(Option.empty[Disposable[S#Tx]])

  protected val auralToViewMap  = TMap.empty[AuralObj[S], NuagesObj[S]]
  protected val viewToAuralMap  = TMap.empty[NuagesObj[S], AuralObj[S]]

  def nuages(implicit tx: S#Tx): Nuages[S] = nuagesH()

  private var  _keyControl: Control with Disposable[S#Tx] = _
  protected def keyControl: Control with Disposable[S#Tx] = _keyControl

  protected def initObservers(repr: Repr)(implicit tx: S#Tx): Unit

  final def init(repr: Repr)(implicit tx: S#Tx): this.type = {
    _keyControl = KeyControl(main)
    deferTx(guiInit())
    initObservers(repr)
    this
  }

  def dispose()(implicit tx: S#Tx): Unit = {
    disposeTransport()
    deferTx(stopAnimation())
    clearSolo()
    observers.foreach(_.dispose())
    disposeAuralObserver()
    transport.dispose()
    auralToViewMap.foreach { case (_, vp) =>
      vp.dispose()
    }
    viewToAuralMap.clear()
    auralToViewMap.clear()
    nodeMap       .dispose()
    // scanMap       .dispose()
    missingScans  .dispose()

    keyControl    .dispose()
  }

//  private[this] val waiting = TxnLocal(Map.empty[S#ID, List[NuagesOutput[S] => Unit]])
//
//  def scanMapGet(id: S#ID)(implicit tx: S#Tx): Option[NuagesOutput[S]] = scanMap.get(id)
//
//  def scanMapPut(id: S#ID, view: NuagesOutput[S])(implicit tx: S#Tx): Unit = {
//    scanMap.put(id, view)
//    implicit val itx = tx.peer
//    if (waiting.isInitialized) waiting.transform { m0 =>
//      m0.get(id).fold(m0) { list =>
//        list.foreach(_.apply(view))
//        m0 - id
//      }
//    }
//  }
//
//  def scanMapRemove(id: S#ID)(implicit tx: S#Tx): Unit = scanMap.remove(id)
//
//  def waitForScanView(id: S#ID)(fun: (NuagesOutput[S]) => Unit)(implicit tx: S#Tx): Unit =
//    waiting.transform { m0 =>
//      val list = m0.getOrElse(id, Nil) :+ fun
//      m0 + (id -> list)
//    } (tx.peer)

  protected def disposeAuralObserver()(implicit tx: S#Tx): Unit = {
    auralReprRef() = None
    auralObserver.swap(None).foreach(_.dispose())
  }

  def selection: Set[NuagesNode[S]] = {
    requireEDT()
    val selectedItems = visualization.getGroup(GROUP_SELECTION)
    import scala.collection.JavaConversions._
    selectedItems.tuples().flatMap {
      case ni: NodeItem =>
        ni.get(COL_NUAGES) match {
          case vn: NuagesNode[S] => Some(vn)
          case _ => None
        }
      case _ => None
    } .toSet
  }

//  def dispose(): Unit = {
//    stopAnimation()
//
//    if (config.collector) println("WARNING! NuagesPanel.dispose -- doesn't handle the collector yet")
//  }

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
    guiCode.transform(_ :+ (() => thunk))

  @inline private def stopAnimation(): Unit = {
    visualization.cancel(ACTION_COLOR)
    visualization.cancel(ACTION_LAYOUT)
  }

  @inline private def startAnimation(): Unit =
    visualization.run(ACTION_COLOR)

  protected def getAuralScanData(aural: AuralObj[S], key: String = Proc.scanMainOut)
                              (implicit tx: S#Tx): Option[(AudioBus, Node)] = aural match {
    case ap: AuralObj.Proc[S] =>
      None // SCAN
//      val d = ap.data
//      for {
//        either  <- d.getScanOut(key)
//        nodeRef <- d.nodeOption
//      } yield {
//        val bus   = either.fold(identity, _.bus)
//        val node  = nodeRef.node
//        (bus, node)
//      }
    case _ => None
  }

  // private def close(p: Container): Unit = p.peer.getParent.remove(p.peer)

  def saveMacro(name: String, sel: Set[NuagesObj[S]]): Unit =
    cursor.step { implicit tx =>
     val copies = Nuages.copyGraph(sel.map(_.obj)(breakOut))

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