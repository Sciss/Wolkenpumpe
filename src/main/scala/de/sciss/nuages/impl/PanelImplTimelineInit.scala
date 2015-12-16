/*
 *  PanelImplTimelineInit.scala
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

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{TxnLike, Disposable, Obj}
import de.sciss.lucre.synth.Sys
import de.sciss.span.SpanLike
import de.sciss.synth.proc.{AuralObj, Timeline, Transport}

import scala.concurrent.stm.Ref

trait PanelImplTimelineInit[S <: Sys[S]] {
  import TxnLike.peer

  // ---- abstract ----

  protected var observers: List[Disposable[S#Tx]]

  protected def auralObserver: Ref[Option[Disposable[S#Tx]]]

  protected def removeLocationHint(obj: Obj[S])(implicit tx: S#Tx): Option[Point2D]

  protected def nodeMap: stm.IdentifierMap[S#ID, S#Tx, NuagesObj[S]]

  protected def transport: Transport[S]

  protected def auralObjAdded(vp: NuagesObj[S], aural: AuralObj[S])(implicit tx: S#Tx): Unit

  protected def auralObjRemoved(aural: AuralObj[S])(implicit tx: S#Tx): Unit

  protected def disposeAuralObserver()(implicit tx: S#Tx): Unit

  protected def disposeObj(obj: Obj[S])(implicit tx: S#Tx): Unit

  protected def main: NuagesPanel[S]

  // ---- impl ----

  protected final def isTimeline = true

  protected final val auralReprRef = Ref(Option.empty[AuralObj.Timeline[S]])

  final protected def initObservers(timeline: Timeline[S])(implicit tx: S#Tx): Unit = {
    observers ::= transport.react { implicit tx => {
      case Transport.ViewAdded(_, auralTimeline: AuralObj.Timeline[S]) =>
        val obs = auralTimeline.contents.react { implicit tx => {
          case AuralObj.Timeline.ViewAdded  (_, timed, view) =>
            nodeMap.get(timed).foreach { vp =>
              auralObjAdded(vp, view)
            }
          case AuralObj.Timeline.ViewRemoved(_, view) =>
            auralObjRemoved(view)
        }}
        disposeAuralObserver()
        auralReprRef () = Some(auralTimeline)
        auralObserver() = Some(obs          )

      case Transport.ViewRemoved(_, auralTL: AuralObj.Timeline[S]) =>
        disposeAuralObserver()

      case Transport.Play(_, pos) => ???!
      case Transport.Stop(_, _) => ???!
      case Transport.Seek(_, pos, isPlaying) => ???!

      case _ =>
    }}
    transport.addObject(timeline)

    observers ::= timeline.changed.react { implicit tx => upd =>
      upd.changes.foreach {
        case Timeline.Added(span, timed) =>
          if (span.contains(transport.position)) addNode(span, timed)
        // XXX TODO - update scheduler

        case Timeline.Removed(span, timed) =>
          if (span.contains(transport.position)) removeNode(span, timed)
        // XXX TODO - update scheduler

        case Timeline.Moved(change, timed) =>
          ???!
      }
    }

    timeline.intersect(transport.position).foreach { case (span, elems) =>
      elems.foreach(addNode(span, _))
    }
  }

  private def removeNode(span: SpanLike, timed: Timeline.Timed[S])(implicit tx: S#Tx): Unit = {
    val id   = timed.id
    val obj  = timed.value
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

  private def addNode(span: SpanLike, timed: Timeline.Timed[S])(implicit tx: S#Tx): Unit = {
    val obj     = timed.value
    val config  = main.config
    val locO    = removeLocationHint(obj)
    implicit val context = main.context
    val vp      = NuagesObj[S](main, locOption = locO, id = timed.id, obj = obj, spanOption = Some(timed.span),
      hasMeter = config.meters, hasSolo = config.soloChannels.isDefined)

    auralReprRef().foreach { auralTimeline =>
      auralTimeline.getView(timed).foreach { auralObj =>
        auralObjAdded(vp, auralObj)
      }
    }
  }
}