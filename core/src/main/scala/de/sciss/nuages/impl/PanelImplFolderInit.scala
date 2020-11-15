/*
 *  PanelImplFolderInit.scala
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

import java.awt.geom.Point2D

import de.sciss.lucre.synth.Txn
import de.sciss.lucre.{Disposable, Folder, IdentMap, Obj, Txn => LTxn}
import de.sciss.span.Span
import de.sciss.proc.{AuralObj, Transport}

import scala.concurrent.stm.Ref

trait PanelImplFolderInit[T <: Txn[T]] {
  import LTxn.peer

  // ---- abstract ----

  protected var observers: List[Disposable[T]]

  protected def auralObserver: Ref[Option[Disposable[T]]]

  protected def removeLocationHint(obj: Obj[T])(implicit tx: T): Option[Point2D]

  protected def transport: Transport[T]

  protected def nodeMap: IdentMap[T, NuagesObj[T]]

  //  protected def auralObjAdded(vp: NuagesObj[T], aural: AuralObj[T])(implicit tx: T): Unit
  //
  //  protected def auralObjRemoved(aural: AuralObj[T])(implicit tx: T): Unit

  protected def disposeAuralObserver()(implicit tx: T): Unit

  protected def disposeObj(obj: Obj[T])(implicit tx: T): Unit

  protected def main: NuagesPanel[T]

  // ---- impl ----

  final def isTimeline = false

  protected final val auralReprRef = Ref(Option.empty[AuralObj.Folder[T]])

  protected final def disposeTransport()(implicit tx: T): Unit = ()

  final protected def initObservers(folder: Folder[T])(implicit tx: T): Unit = {
    observers ::= transport.react { implicit tx => {
      case Transport.ViewAdded(_, auralFolder: AuralObj.Folder[T]) =>
        val obs = auralFolder.contents.react { implicit tx => {
          case AuralObj.Container.ViewAdded  (_, id, view) =>
            nodeMap.get(id).foreach { vp =>
              vp.auralObjAdded(view)
            }
          case AuralObj.Container.ViewRemoved(_, id, view) =>
            nodeMap.get(id).foreach { vp =>
              vp.auralObjRemoved(view)
            }
        }}
        disposeAuralObserver()
        auralReprRef () = Some(auralFolder)
        auralObserver() = Some(obs        )

      case Transport.ViewRemoved(_, _: AuralObj.Timeline[T]) =>
        disposeAuralObserver()

      case _ =>
    }}
    transport.addObject(folder)

    observers ::= folder.changed.react { implicit tx => upd =>
      upd.changes.foreach {
        case Folder.Added  (_ /* index */, child) => addNode   (child)
        case Folder.Removed(_ /* index */, child) => removeNode(child)
      }
    }

    folder.iterator.foreach(addNode)
  }

  private def addNode(obj: Obj[T])(implicit tx: T): Unit = {
    val config  = main.config
    val locO    = removeLocationHint(obj)
    implicit val context: NuagesContext[T] = main.context
    val vp      = NuagesObj[T](main, locOption = locO, id = obj.id, obj = obj,
      spanValue = Span.All, spanOption = None,
      hasMeter = config.meters, hasSolo = config.soloChannels.isDefined)

    auralReprRef().foreach { auralFolder =>
      auralFolder.getView(obj).foreach { auralObj =>
        vp.auralObjAdded(auralObj)
      }
    }
  }

  private def removeNode(obj: Obj[T])(implicit tx: T): Unit = {
    val id   = obj.id
    nodeMap.get(id).foreach { vp =>
      vp.dispose()
      disposeObj(obj)

      // note: we could look for `solo` and clear it
      // if relevant; but the bus-reader will automatically
      // go to dummy, so let's just save the effort.
      // orphaned solo will be cleared when calling
      // `setSolo` another time or upon frame disposal.
    }
  }
}