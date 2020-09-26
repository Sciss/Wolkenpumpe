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

import de.sciss.lucre.{Cursor, Disposable, Folder, IdentMap, ListObj, Obj, Source, TxnLike, Txn => LTxn}
import de.sciss.lucre.swing.ListView
import de.sciss.lucre.swing.LucreSwing.{defer, deferTx, requireEDT}
import de.sciss.lucre.synth.Txn
import de.sciss.nuages.Nuages.Surface
import de.sciss.serial.TFormat
import de.sciss.synth.proc
import de.sciss.synth.proc.{AuralObj, Timeline, Transport, Universe}
import prefuse.controls.Control
import prefuse.visual.NodeItem

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.{Ref, TxnLocal}
import scala.util.control.NonFatal

object PanelImpl {
  var DEBUG = false

  def apply[T <: Txn[T]](nuages: Nuages[T], config: Nuages.Config)
                        (implicit tx: T, universe: Universe[T], context: NuagesContext[T]): NuagesPanel[T] = {
    val nuagesH       = tx.newHandle(nuages)

    val listGen       = mkListView(nuages.generators)
    val listFlt1      = mkListView(nuages.filters   )
    val listCol1      = mkListView(nuages.collectors)
    val listFlt2      = mkListView(nuages.filters   )
    val listCol2      = mkListView(nuages.collectors)
    val listMacro     = mkListView(nuages.macros    )

    val nodeMap       = tx.newIdentMap[NuagesObj[T]]
    // val scanMap       = tx.newIdentMap[NuagesOutput[T]] // ScanInfo [T]]
    val missingScans  = tx.newIdentMap[List[NuagesAttribute[T]]]
    val transport     = Transport[T](universe)
    val surface       = nuages.surface
    // transport.addObject(surface.peer)

    surface match {
      case Surface.Timeline(tl) =>
        new PanelImplTimeline[T](nuagesH, nodeMap, /* scanMap, */ missingScans, config, transport,
          listGen = listGen, listFlt1 = listFlt1, listCol1 = listCol1, listFlt2 = listFlt2, listCol2 = listCol2,
          listMacro = listMacro)
          .init(tl)

      case Surface.Folder(f) =>
        new PanelImplFolder[T](nuagesH, nodeMap, /* scanMap, */ missingScans, config, transport,
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

  def mkListView[T <: Txn[T]](folderOpt: Option[Folder[T]])
                             (implicit tx: T): ListView[T, Obj[T], Unit] = {
    import proc.Implicits._
    val h = ListView.Handler[T, Obj[T], Unit /* Obj.Update[T] */] { implicit tx => obj => obj.name } (_ => (_, _) => None)
    implicit val fmt: TFormat[T, ListObj[T, Obj[T]]] =
      ListObj.format[T, Obj[T] /* , Unit */ /* Obj.Update[T] */]

    // val res = ListView[T, Obj[T], Unit /* Obj.Update[T] */, String](folder, h)
    val res = ListView.empty[T, Obj[T], Unit /* Obj.Update[T] */, String](h)
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

final class PanelImplTimeline[T <: Txn[T]](protected val nuagesH: Source[T, Nuages[T]],
                                           protected val nodeMap: IdentMap[T, NuagesObj[T]],
//     protected val scanMap: stm.IdentMap[Ident[T], T, NuagesOutput[T]],
     protected val missingScans: IdentMap[T, List[NuagesAttribute[T]]],
     val config   : Nuages.Config,
     val transport: Transport[T],
     protected val listGen  : ListView[T, Obj[T], Unit /* Obj.Update[T] */],
     protected val listFlt1 : ListView[T, Obj[T], Unit /* Obj.Update[T] */],
     protected val listCol1 : ListView[T, Obj[T], Unit /* Obj.Update[T] */],
     protected val listFlt2 : ListView[T, Obj[T], Unit /* Obj.Update[T] */],
     protected val listCol2 : ListView[T, Obj[T], Unit /* Obj.Update[T] */],
     protected val listMacro: ListView[T, Obj[T], Unit /* Obj.Update[T] */])
    (implicit val universe: Universe[T],
     val context: NuagesContext[T])
  extends PanelImpl[T, Timeline[T], AuralObj.Timeline[T]]
  with PanelImplTimelineInit[T]

final class PanelImplFolder[T <: Txn[T]](protected val nuagesH: Source[T, Nuages[T]],
                                         protected val nodeMap: IdentMap[T, NuagesObj[T]],
//     protected val scanMap: stm.IdentMap[Ident[T], T, NuagesOutput[T]],
     protected val missingScans: IdentMap[T, List[NuagesAttribute[T]]],
     val config   : Nuages.Config,
     val transport: Transport[T],
     protected val listGen  : ListView[T, Obj[T], Unit /* Obj.Update[T] */],
     protected val listFlt1 : ListView[T, Obj[T], Unit /* Obj.Update[T] */],
     protected val listCol1 : ListView[T, Obj[T], Unit /* Obj.Update[T] */],
     protected val listFlt2 : ListView[T, Obj[T], Unit /* Obj.Update[T] */],
     protected val listCol2 : ListView[T, Obj[T], Unit /* Obj.Update[T] */],
     protected val listMacro: ListView[T, Obj[T], Unit /* Obj.Update[T] */])
    (implicit val universe: Universe[T],
     val context: NuagesContext[T])
  extends PanelImpl[T, Folder[T], AuralObj.Folder[T]]
  with PanelImplFolderInit[T]

// nodeMap: uses timed-id as key
trait PanelImpl[T <: Txn[T], Repr <: Obj[T], AuralRepr <: AuralObj[T]]
  extends NuagesPanel[T]
  // here comes your cake!
  with PanelImplDialogs[T]
  with PanelImplTxnFuns[T]
  with PanelImplReact  [T]
  with PanelImplMixer  [T]
  with PanelImplGuiInit[T]
  // with PanelImplGuiFuns[T]
  {
  panel =>

  import NuagesPanel.{COL_NUAGES, GROUP_SELECTION}
  import PanelImpl._
  import LTxn.peer

  // ---- abstract ----

  protected def nuagesH: Source[T, Nuages[T]]

  protected def auralReprRef: Ref[Option[AuralRepr]]

  protected def disposeTransport()(implicit tx: T): Unit

  protected def initObservers(repr: Repr)(implicit tx: T): Unit

  // ---- impl ----

  protected final def main: NuagesPanel[T] = this

  final def cursor: Cursor[T] = universe.cursor

  protected final var observers: List[Disposable[T]] = Nil
  protected final val auralObserver                     = Ref(Option.empty[Disposable[T]])

  final def nuages(implicit tx: T): Nuages[T] = nuagesH()

  private[this] var  _keyControl: Control with Disposable[T] = _
  protected final def keyControl: Control with Disposable[T] = _keyControl

  final def init(repr: Repr)(implicit tx: T): this.type = {
    _keyControl = KeyControl(main)
    deferTx(guiInit())
    initObservers(repr)
    this
  }

  final def dispose()(implicit tx: T): Unit = {
    disposeTransport()
    disposeNodes()
    deferTx(stopAnimation())
    disposeSoloSynth()
    observers.foreach(_.dispose())
    disposeAuralObserver()
    transport .dispose()
    keyControl.dispose()
  }

  protected final def disposeAuralObserver()(implicit tx: T): Unit = {
    auralReprRef() = None
    auralObserver.swap(None).foreach(_.dispose())
  }

  final def selection: Set[NuagesNode[T]] = {
    requireEDT()
    val selectedItems = visualization.getGroup(GROUP_SELECTION)
    import scala.collection.JavaConverters._
    selectedItems.tuples().asScala.flatMap {
      case ni: NodeItem =>
        ni.get(COL_NUAGES) match {
          case vn: NuagesNode[T] => Some(vn)
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

  final def saveMacro(name: String, sel: Set[NuagesObj[T]]): Unit =
    cursor.step { implicit tx =>
     val copies = Nuages.copyGraph(sel.iterator.map(_.obj).toIndexedSeq)

      val macroF = Folder[T]()
      copies.foreach(macroF.addLast)
      val nuagesF = panel.nuages.folder
      import proc.Implicits._
      val parent = nuagesF.iterator.collect {
        case parentObj: Folder[T] if parentObj.name == Nuages.NameMacros => parentObj
      } .toList.headOption.getOrElse {
        val res = Folder[T]()
        res.name = Nuages.NameMacros
        nuagesF.addLast(res)
        res
      }

      macroF.name = name
      parent.addLast(macroF)
    }
}