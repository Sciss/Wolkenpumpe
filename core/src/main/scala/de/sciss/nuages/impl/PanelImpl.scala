/*
 *  PanelImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2020 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import java.awt.Color

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, Folder, Obj, TxnLike}
import de.sciss.lucre.swing.ListView
import de.sciss.lucre.swing.LucreSwing.{defer, deferTx, requireEDT}
import de.sciss.lucre.synth.Sys
import de.sciss.nuages.Nuages.Surface
import de.sciss.serial.Serializer
import de.sciss.synth.proc
import de.sciss.synth.proc.{AuralObj, Timeline, Transport, Universe}
import prefuse.controls.Control
import prefuse.visual.NodeItem

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.{Ref, TxnLocal}
import scala.util.control.NonFatal

object PanelImpl {
  var DEBUG = false

  def apply[S <: Sys[S]](nuages: Nuages[S], config: Nuages.Config)
                        (implicit tx: S#Tx, universe: Universe[S], context: NuagesContext[S]): NuagesPanel[S] = {
    val nuagesH       = tx.newHandle(nuages)

    val listGen       = mkListView(nuages.generators)
    val listFlt1      = mkListView(nuages.filters   )
    val listCol1      = mkListView(nuages.collectors)
    val listFlt2      = mkListView(nuages.filters   )
    val listCol2      = mkListView(nuages.collectors)
    val listMacro     = mkListView(nuages.macros    )

    val nodeMap       = tx.newInMemoryIdMap[NuagesObj[S]]
    // val scanMap       = tx.newInMemoryIdMap[NuagesOutput[S]] // ScanInfo [S]]
    val missingScans  = tx.newInMemoryIdMap[List[NuagesAttribute[S]]]
    val transport     = Transport[S](universe)
    val surface       = nuages.surface
    // transport.addObject(surface.peer)

    surface match {
      case Surface.Timeline(tl) =>
        new PanelImplTimeline[S](nuagesH, nodeMap, /* scanMap, */ missingScans, config, transport,
          listGen = listGen, listFlt1 = listFlt1, listCol1 = listCol1, listFlt2 = listFlt2, listCol2 = listCol2,
          listMacro = listMacro)
          .init(tl)

      case Surface.Folder(f) =>
        new PanelImplFolder[S](nuagesH, nodeMap, /* scanMap, */ missingScans, config, transport,
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
                             (implicit tx: S#Tx): ListView[S, Obj[S], Unit] = {
    import proc.Implicits._
    val h = ListView.Handler[S, Obj[S], Unit /* Obj.Update[S] */] { implicit tx => obj => obj.name } (_ => (_, _) => None)
    implicit val ser: Serializer[S#Tx, S#Acc, stm.List[S, Obj[S]]] =
      stm.List.serializer[S, Obj[S] /* , Unit */ /* Obj.Update[S] */]

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
                                           protected val nodeMap: stm.IdentifierMap[S#Id, S#Tx, NuagesObj[S]],
//     protected val scanMap: stm.IdentifierMap[S#Id, S#Tx, NuagesOutput[S]],
     protected val missingScans: stm.IdentifierMap[S#Id, S#Tx, List[NuagesAttribute[S]]],
     val config   : Nuages.Config,
     val transport: Transport[S],
     protected val listGen  : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listFlt1 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listCol1 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listFlt2 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listCol2 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listMacro: ListView[S, Obj[S], Unit /* Obj.Update[S] */])
    (implicit val universe: Universe[S],
     val context: NuagesContext[S])
  extends PanelImpl[S, Timeline[S], AuralObj.Timeline[S]]
  with PanelImplTimelineInit[S]

final class PanelImplFolder[S <: Sys[S]](protected val nuagesH: stm.Source[S#Tx, Nuages[S]],
                                         protected val nodeMap: stm.IdentifierMap[S#Id, S#Tx, NuagesObj[S]],
//     protected val scanMap: stm.IdentifierMap[S#Id, S#Tx, NuagesOutput[S]],
     protected val missingScans: stm.IdentifierMap[S#Id, S#Tx, List[NuagesAttribute[S]]],
     val config   : Nuages.Config,
     val transport: Transport[S],
     protected val listGen  : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listFlt1 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listCol1 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listFlt2 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listCol2 : ListView[S, Obj[S], Unit /* Obj.Update[S] */],
     protected val listMacro: ListView[S, Obj[S], Unit /* Obj.Update[S] */])
    (implicit val universe: Universe[S],
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

  import NuagesPanel.{COL_NUAGES, GROUP_SELECTION}
  import PanelImpl._
  import TxnLike.peer

  // ---- abstract ----

  protected def nuagesH: stm.Source[S#Tx, Nuages[S]]

  protected def auralReprRef: Ref[Option[AuralRepr]]

  protected def disposeTransport()(implicit tx: S#Tx): Unit

  protected def initObservers(repr: Repr)(implicit tx: S#Tx): Unit

  // ---- impl ----

  protected final def main: NuagesPanel[S] = this

  final def cursor: stm.Cursor[S] = universe.cursor

  protected final var observers: List[Disposable[S#Tx]] = Nil
  protected final val auralObserver                     = Ref(Option.empty[Disposable[S#Tx]])

  final def nuages(implicit tx: S#Tx): Nuages[S] = nuagesH()

  private[this] var  _keyControl: Control with Disposable[S#Tx] = _
  protected final def keyControl: Control with Disposable[S#Tx] = _keyControl

  final def init(repr: Repr)(implicit tx: S#Tx): this.type = {
    _keyControl = KeyControl(main)
    deferTx(guiInit())
    initObservers(repr)
    this
  }

  final def dispose()(implicit tx: S#Tx): Unit = {
    disposeTransport()
    disposeNodes()
    deferTx(stopAnimation())
    disposeSoloSynth()
    observers.foreach(_.dispose())
    disposeAuralObserver()
    transport .dispose()
    keyControl.dispose()
  }

  protected final def disposeAuralObserver()(implicit tx: S#Tx): Unit = {
    auralReprRef() = None
    auralObserver.swap(None).foreach(_.dispose())
  }

  final def selection: Set[NuagesNode[S]] = {
    requireEDT()
    val selectedItems = visualization.getGroup(GROUP_SELECTION)
    import scala.collection.JavaConverters._
    selectedItems.tuples().asScala.flatMap {
      case ni: NodeItem =>
        ni.get(COL_NUAGES) match {
          case vn: NuagesNode[S] => Some(vn)
          case _ => None
        }
      case _ => None
    } .toSet
  }

  private[this] val guiCode = TxnLocal(init = Vector.empty[() => Unit], afterCommit = handleGUI)

  private def handleGUI(seq: Vec[() => Unit]): Unit = {
    def exec(): Unit = visualization.synchronized {
      stopAnimation()
      // AGGR_LOCK = true
      seq.foreach { fun =>
        try {
          fun()
        } catch {
          case NonFatal(e) => e.printStackTrace()
        }
      }
      // AGGR_LOCK = false
      startAnimation()
    }

    defer(exec())
  }

  final def deferVisTx(thunk: => Unit)(implicit tx: TxnLike): Unit =
    guiCode.transform(_ :+ (() => thunk))

  @inline private def stopAnimation(): Unit = {
    visualization.cancel(ACTION_COLOR)
    visualization.cancel(ACTION_LAYOUT)
  }

  @inline private def startAnimation(): Unit =
    visualization.run(ACTION_COLOR)

  final def saveMacro(name: String, sel: Set[NuagesObj[S]]): Unit =
    cursor.step { implicit tx =>
     val copies = Nuages.copyGraph(sel.iterator.map(_.obj).toIndexedSeq)

      val macroF = Folder[S]()
      copies.foreach(macroF.addLast)
      val nuagesF = panel.nuages.folder
      import proc.Implicits._
      val parent = nuagesF.iterator.collect {
        case parentObj: Folder[S] if parentObj.name == Nuages.NameMacros => parentObj
      } .toList.headOption.getOrElse {
        val res = Folder[S]()
        res.name = Nuages.NameMacros
        nuagesF.addLast(res)
        res
      }

      macroF.name = name
      parent.addLast(macroF)
    }
}