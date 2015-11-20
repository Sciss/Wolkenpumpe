package de.sciss.nuages
package impl

import java.awt.geom.Point2D

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Obj, Disposable, Sys}
import de.sciss.synth.proc.{AuralObj, Transport, Folder}

trait PanelImplFolderInit[S <: Sys[S]] {
  // ---- abstract ----

  protected var observers: List[Disposable[S#Tx]]

  protected def removeLocationHint(obj: Obj[S])(implicit tx: S#Tx): Option[Point2D]

  protected def transport: Transport[S]

  protected def nodeMap: stm.IdentifierMap[S#ID, S#Tx, NuagesObj[S]]

  protected def auralObjAdded(vp: NuagesObj[S], aural: AuralObj[S])(implicit tx: S#Tx): Unit

  protected def auralObjRemoved(aural: AuralObj[S])(implicit tx: S#Tx): Unit

  protected def disposeAuralObserver()(implicit tx: S#Tx): Unit

  protected def main: NuagesPanel[S]

  // ---- impl ----

  final def init(folder: Folder[S])(implicit tx: S#Tx): this.type = {
    observers ::= transport.react { implicit tx => {
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
        ???
//        auralTimeline.set(Some(auralTL))(tx.peer)
//        auralObserver.set(Some(obs    ))(tx.peer)

      case Transport.ViewRemoved(_, auralTL: AuralObj.Timeline[S]) =>
        disposeAuralObserver()

      case _ =>
    }}
    transport.addObject(folder)

    observers ::= folder.changed.react { implicit tx => upd =>
      upd.changes.foreach {
//        case Timeline.Added(span, timed) =>
//          if (span.contains(transport.position)) addNode(span, timed)
//        // XXX TODO - update scheduler
//
//        case Timeline.Removed(span, timed) =>
//          if (span.contains(transport.position)) removeNode(span, timed)
//        // XXX TODO - update scheduler


        case other => if (PanelImpl.DEBUG) println(s"OBSERVED: $other")
      }
    }

    folder.iterator.foreach(addNode)
    this
  }

  private def addNode(obj: Obj[S])(implicit tx: S#Tx): Unit = {
    val config  = main.config
    val locO    = removeLocationHint(obj)
    implicit val context = main.context
    val vp    = ??? // NuagesObj[S](main, locO, timed, hasMeter = config.meters, hasSolo = config.soloChannels.isDefined)

    ???
//    auralTimeline.get(tx.peer).foreach { auralTL =>
//      auralTL.getView(timed).foreach { auralObj =>
//        auralObjAdded(vp, auralObj)
//      }
//    }
  }
}
