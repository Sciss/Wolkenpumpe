/*
 *  PanelImplTimelineInit.scala
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
import de.sciss.lucre.{Disposable, IdentMap, Obj, Source, Txn => LTxn}
import de.sciss.span.SpanLike
import de.sciss.proc.Timeline.Timed
import de.sciss.proc.{AuralObj, Timeline, Transport}

import scala.concurrent.stm.Ref

trait PanelImplTimelineInit[T <: Txn[T]] extends NuagesTimelineBase[T] {
  import LTxn.peer

  // ---- abstract ----

  protected var observers: List[Disposable[T]]

  protected def auralObserver: Ref[Option[Disposable[T]]]

  protected def removeLocationHint(obj: Obj[T])(implicit tx: T): Option[Point2D]

  protected def nodeMap: IdentMap[T, NuagesObj[T]]

  //  protected def auralObjAdded(vp: NuagesObj[T], aural: AuralObj[T])(implicit tx: T): Unit
  //
  //  protected def auralObjRemoved(aural: AuralObj[T])(implicit tx: T): Unit

  protected def disposeAuralObserver()(implicit tx: T): Unit

  protected def disposeObj(obj: Obj[T])(implicit tx: T): Unit

  protected def main: NuagesPanel[T]

  // ---- impl ----

  final def isTimeline = true

  protected final val auralReprRef = Ref(Option.empty[AuralObj.Timeline[T]])

  protected final var timelineH: Source[T, Timeline[T]] = _

  protected final def initObservers(timeline: Timeline[T])(implicit tx: T): Unit = {
    timelineH = tx.newHandle(timeline)

    val t = transport
    observers ::= t.react { implicit tx => {
      case Transport.ViewAdded(_, auralTimeline: AuralObj.Timeline[T]) =>
        val obs = auralTimeline.contents.react { implicit tx => {
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
        auralReprRef () = Some(auralTimeline)
        auralObserver() = Some(obs          )

      case Transport.ViewRemoved(_, _: AuralObj.Timeline[T]) =>
        disposeAuralObserver()

      case _ =>
    }}
    t.addObject(timeline)

    initPosition()
    initTimeline(timeline)
    initTransport()
  }

  protected final def frameOffset: Long = 0L

  protected final def addNode(span: SpanLike, timed: Timed[T])(implicit tx: T): Unit = {
    log(s"nuages timeline addNode $timed")
    val obj     = timed.value
    val config  = main.config
    val locO    = removeLocationHint(obj)
    implicit val context: NuagesContext[T] = main.context
    val vp      = NuagesObj[T](main, locOption = locO, id = timed.id, obj = obj,
      spanValue = span, spanOption = Some(timed.span),
      hasMeter = config.meters, hasSolo = config.soloChannels.isDefined)

    for {
      auralTimeline <- auralReprRef()
      auralObj      <- auralTimeline.getView(timed)
    } {
      vp.auralObjAdded(auralObj)
    }
  }

  protected final def removeNode(span: SpanLike, timed: Timed[T])(implicit tx: T): Unit = {
    log(s"nuages timeline removeNode $timed")
    val id  = timed.id
    val obj = timed.value
    val vp  = nodeMap.getOrElse(id, throw new IllegalStateException(s"View for $timed not found"))
    vp.dispose()
    disposeObj(obj)

    // note: we could look for `solo` and clear it
    // if relevant; but the bus-reader will automatically
    // go to dummy, so let's just save the effort.
    // orphaned solo will be cleared when calling
    // `setSolo` another time or upon frame disposal.
  }
}