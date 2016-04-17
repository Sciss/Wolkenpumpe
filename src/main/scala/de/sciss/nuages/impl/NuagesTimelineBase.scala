/*
 *  NuagesTimelineBase.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2016 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, Sys, TxnLike}
import de.sciss.span.{Span, SpanLike}
import de.sciss.synth.proc.Timeline
import de.sciss.synth.proc.Timeline.Timed

trait NuagesTimelineBase[S <: Sys[S]] extends NuagesScheduledBase[S] {

  import TxnLike.peer

  // ---- abstract ----

  protected def timelineH: stm.Source[S#Tx, Timeline[S]]

  protected def addNode   (span: SpanLike, timed: Timed[S])(implicit tx: S#Tx): Unit
  protected def removeNode(span: SpanLike, timed: Timed[S])(implicit tx: S#Tx): Unit

  // ---- impl ----

  private[this] var observer: Disposable[S#Tx] = _

  /** Calls `initTimelineObserver` followed by creating live views.
    * This must be called after `initPosition` and before `initTransport`.
    */
  final protected def initTimeline(tl: Timeline[S])(implicit tx: S#Tx): Unit = {
    initTimelineObserver(tl)
    val offset0 = currentOffset()
    tl.intersect(offset0).foreach { case (span, elems) =>
      elems.foreach(addNode(span, _))
    }
  }

  final protected def initTimelineObserver(tl: Timeline[S])(implicit tx: S#Tx): Unit = {
    observer = tl.changed.react { implicit tx => upd =>
      if (!isDisposed) upd.changes.foreach {
        case Timeline.Added  (span, timed) => addRemoveNode(span, timed, add = true )
        case Timeline.Removed(span, timed) => addRemoveNode(span, timed, add = false)
        case Timeline.Moved(change, timed) =>
          val t     = transport
          val time  = currentOffset()
          val rem   = change.before.contains(time)
          val add   = change.now   .contains(time)

          if (rem || add) {
            offsetRef() = time
          }

          if (rem) removeNode(change.before, timed)
          if (add) addNode   (change.now   , timed)

          if (t.isPlaying && {
            val from = Span.from(time)
            change.before.overlaps(from) || change.now.overlaps(from)
          }) {
            // new child might start or stop before currently
            // scheduled next event. Simply reset scheduler
            reschedule(time)
          }
      }
    }
  }

  private[this] def addRemoveNode(span: SpanLike, timed: Timed[S], add: Boolean)(implicit tx: S#Tx): Unit = {
    val t    = transport
    val time = currentOffset()
    if (span.contains(time)) {
      offsetRef() = time
      if (add) addNode(span, timed) else removeNode(span, timed)
    }
    if (t.isPlaying && span.overlaps(Span.from(time))) {
      // new child might start or stop before currently
      // scheduled next event. Simply reset scheduler
      reschedule(time)
    }
  }

  protected final def seek(before: Long, now: Long)(implicit tx: S#Tx): Unit = {
    val timeline = timelineH()
    // there are two possibilities:
    // - use timeline.rangeSearch to determine
    //   the regions that disappeared and those that appeared
    // - use one intersect, then diff against the view-set
    // The former is a bit more elegant but also more complicated
    // to get right -- see section 5.11.4 of my thesis:
    //
    // before < now
    // - regions to remove are those whose
    //   start is contained in Span.until(before + 1) (i.e. they have started)
    //   and whose
    //   stop is contained in Span(before + 1, now + 1) (i.e. they haven't been stopped but will have been)
    //
    // before > now
    //
    val beforeP = before + 1
    val nowP    = now    + 1
    val (toRemove, toAdd) = if (before < now) {
      val iv1   = Span(beforeP, nowP)
      val _rem  = timeline.rangeSearch(start = Span.until(beforeP), stop = iv1)
      val _add  = timeline.rangeSearch(start = iv1, stop = Span.from(nowP))
      (_rem, _add)
    } else {
      val iv1   = Span(nowP, beforeP)
      val _rem  = timeline.rangeSearch(start = iv1, stop = Span.from(beforeP))
      val _add  = timeline.rangeSearch(start = Span.until(nowP), stop = iv1)
      (_rem, _add)
    }
    toRemove.foreach { case (span, elems) =>
      elems.foreach(removeNode(span, _))
    }
    toAdd   .foreach { case (span, elems) =>
      elems.foreach(addNode(span, _))
    }
  }

  protected final def eventAfter(offset: Long)(implicit tx: S#Tx): Long =
    timelineH().eventAfter(offset).getOrElse(Long.MaxValue)

  protected final def processEvent(offset: Long)(implicit tx: S#Tx): Unit = {
    val timeline          = timelineH()
    val (startIt, stopIt) = timeline.eventsAt(offset)

    stopIt .foreach { case (span, xs) => xs.foreach(removeNode(span, _)) }
    startIt.foreach { case (span, xs) => xs.foreach(addNode   (span, _)) }
  }
}