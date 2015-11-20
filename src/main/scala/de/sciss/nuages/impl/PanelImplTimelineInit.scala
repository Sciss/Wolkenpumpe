package de.sciss.nuages
package impl

import java.awt.geom.Point2D

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, Obj}
import de.sciss.lucre.synth.Sys
import de.sciss.span.SpanLike
import de.sciss.synth.proc.{AuralObj, Timeline, Transport}

import scala.concurrent.stm.Ref

trait PanelImplTimelineInit[S <: Sys[S]] {
  // _: PanelImpl[S] =>

  // ---- abstract ----

//  protected var transportObserver: Disposable[S#Tx]
//  protected var timelineObserver : Disposable[S#Tx]

  protected var observers: List[Disposable[S#Tx]]

  protected def auralObserver: Ref[Option[Disposable[S#Tx]]]
//  protected def auralTimeline: Ref[Option[AuralObj.Timeline[S]]]

  protected def removeLocationHint(obj: Obj[S])(implicit tx: S#Tx): Option[Point2D]

  protected def nodeMap: stm.IdentifierMap[S#ID, S#Tx, NuagesObj[S]]

  protected def transport: Transport[S]

  protected def auralObjAdded(vp: NuagesObj[S], aural: AuralObj[S])(implicit tx: S#Tx): Unit

  protected def auralObjRemoved(aural: AuralObj[S])(implicit tx: S#Tx): Unit

  protected def disposeAuralObserver()(implicit tx: S#Tx): Unit

  protected def disposeObj(obj: Obj[S])(implicit tx: S#Tx): Unit

//  def addNode   (span: SpanLike, timed: Timeline.Timed[S])(implicit tx: S#Tx): Unit
//  def removeNode(span: SpanLike, timed: Timeline.Timed[S])(implicit tx: S#Tx): Unit

  protected def main: NuagesPanel[S]

  // ---- impl ----

  private val auralTimeline = Ref(Option.empty[AuralObj.Timeline[S]])

  def init(timeline: Timeline[S])(implicit tx: S#Tx): this.type = {
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
        auralTimeline.set(Some(auralTL))(tx.peer)
        auralObserver.set(Some(obs    ))(tx.peer)

      case Transport.ViewRemoved(_, auralTL: AuralObj.Timeline[S]) =>
        disposeAuralObserver()

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


        case other => if (PanelImpl.DEBUG) println(s"OBSERVED: $other")
      }
    }

    timeline.intersect(transport.position).foreach { case (span, elems) =>
      elems.foreach(addNode(span, _))
    }
    this
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
    val vp      = NuagesObj[S](main, locO, timed, hasMeter = config.meters, hasSolo = config.soloChannels.isDefined)

    auralTimeline.get(tx.peer).foreach { auralTL =>
      auralTL.getView(timed).foreach { auralObj =>
        auralObjAdded(vp, auralObj)
      }
    }
  }
}
