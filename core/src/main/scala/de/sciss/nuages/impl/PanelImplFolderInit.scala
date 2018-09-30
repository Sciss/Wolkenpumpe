/*
 *  PanelImplFolderInit.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2018 Hanns Holger Rutz. All rights reserved.
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

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, Folder, Obj, TxnLike}
import de.sciss.lucre.synth.Sys
import de.sciss.span.Span
import de.sciss.synth.proc.{AuralObj, Transport}

import scala.concurrent.stm.Ref

trait PanelImplFolderInit[S <: Sys[S]] {
  import TxnLike.peer

  // ---- abstract ----

  protected var observers: List[Disposable[S#Tx]]

  protected def auralObserver: Ref[Option[Disposable[S#Tx]]]

  protected def removeLocationHint(obj: Obj[S])(implicit tx: S#Tx): Option[Point2D]

  protected def transport: Transport[S]

  protected def nodeMap: stm.IdentifierMap[S#Id, S#Tx, NuagesObj[S]]

  //  protected def auralObjAdded(vp: NuagesObj[S], aural: AuralObj[S])(implicit tx: S#Tx): Unit
  //
  //  protected def auralObjRemoved(aural: AuralObj[S])(implicit tx: S#Tx): Unit

  protected def disposeAuralObserver()(implicit tx: S#Tx): Unit

  protected def disposeObj(obj: Obj[S])(implicit tx: S#Tx): Unit

  protected def main: NuagesPanel[S]

  // ---- impl ----

  final def isTimeline = false

  protected final val auralReprRef = Ref(Option.empty[AuralObj.Folder[S]])

  protected final def disposeTransport()(implicit tx: S#Tx): Unit = ()

  final protected def initObservers(folder: Folder[S])(implicit tx: S#Tx): Unit = {
    observers ::= transport.react { implicit tx => {
      case Transport.ViewAdded(_, auralFolder: AuralObj.Folder[S]) =>
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

      case Transport.ViewRemoved(_, _: AuralObj.Timeline[S]) =>
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

  private def addNode(obj: Obj[S])(implicit tx: S#Tx): Unit = {
    val config  = main.config
    val locO    = removeLocationHint(obj)
    implicit val context: NuagesContext[S] = main.context
    val vp      = NuagesObj[S](main, locOption = locO, id = obj.id, obj = obj,
      spanValue = Span.All, spanOption = None,
      hasMeter = config.meters, hasSolo = config.soloChannels.isDefined)

    auralReprRef().foreach { auralFolder =>
      auralFolder.getView(obj).foreach { auralObj =>
        vp.auralObjAdded(auralObj)
      }
    }
  }

  private def removeNode(obj: Obj[S])(implicit tx: S#Tx): Unit = {
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