/*
 *  NuagesTimelineTransport.scala
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

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{TxnLike, Disposable, Sys}
import de.sciss.span.{SpanLike, Span}
import de.sciss.synth.proc.{Transport, Timeline}
import de.sciss.synth.proc.Timeline.Timed

import scala.concurrent.stm.Ref

trait NuagesTimelineTransport[S <: Sys[S]] {

  import TxnLike.peer

  // ---- abstract ----

  protected def timelineH: stm.Source[S#Tx, Timeline[S]]

  protected def transport: Transport[S]

  protected def currentFrame()(implicit tx: S#Tx): Long

  protected def addNode   (timed: Timed[S])(implicit tx: S#Tx): Unit
  protected def removeNode(timed: Timed[S])(implicit tx: S#Tx): Unit

  // ---- impl ----

  private[this] val tokenRef  = Ref(-1)

  // last frame for which view-state has been updated.
  // will be initialised in `initTransport`
  private[this] val frameRef  = Ref(0L)

  protected def disposeTransport()(implicit tx: S#Tx): Unit = {
    disposed() = true
    clearSched()
    observers.foreach(_.dispose())
  }

  private[this] var observers = List.empty[Disposable[S#Tx]]

  // It may happen that the transport observer is still
  // invoked after `dispose`, i.e. when `dispose` was
  // called from another transport observer. Therefore,
  // we have two strategies:
  // - remove the assertions in `removeNode` in sub-classes
  // - maintain a `disposed` state.
  // We go for this second approach at least as we develop,
  // so we keep as many checks in place as possible.
  private[this] val disposed  = Ref(false)

  final protected def initTransport(t: Transport[S], tl: Timeline[S])(implicit tx: S#Tx): Unit = {
    observers ::= t.react { implicit tx => upd => if (!disposed()) upd match {
      case Transport.Play(_, _) => play()
      case Transport.Stop(_, _) => stop()
      case Transport.Seek(_, pos, isPlaying) =>
        if (isPlaying) stop()
        seek(before = frameRef(), now = pos)
        if (isPlaying) play()
      case _ =>
    }}

    observers ::= tl.changed.react { implicit tx => upd =>
      if (!disposed()) upd.changes.foreach {
        case Timeline.Added  (span, timed) => addRemoveNode(span, timed, add = true )
        case Timeline.Removed(span, timed) => addRemoveNode(span, timed, add = false)
        case Timeline.Moved(change, timed) =>
          val t     = transport
          val time  = currentFrame()
          val rem   = change.before.contains(time)
          val add   = change.now.contains(time)

          if (rem || add) {
            frameRef() = time
            // println(s"frameRef = $time")
          }

          if (rem) removeNode(timed)
          if (add) addNode   (timed)

          if (t.isPlaying && {
            val from = Span.from(time)
            change.before.overlaps(from) || change.now.overlaps(from)
          }) {
            // new child might start or stop before currently
            // scheduled next event. Simply reset scheduler
            val timeline = timelineH()
            clearSched()
            schedNext(timeline, time)
          }
      }
    }

    val frame0 = currentFrame()
    frameRef() = frame0
    tl.intersect(frame0).foreach { case (span, elems) =>
      elems.foreach(addNode)
    }

    if (t.isPlaying) play()
  }

  private[this] def addRemoveNode(span: SpanLike, timed: Timed[S], add: Boolean)(implicit tx: S#Tx): Unit = {
    val t    = transport
    val time = currentFrame()
    if (span.contains(time)) {
      frameRef() = time
      // println(s"frameRef = $time")
      if (add) addNode(timed) else removeNode(timed)
    }
    if (t.isPlaying && span.overlaps(Span.from(time))) {
      // new child might start or stop before currently
      // scheduled next event. Simply reset scheduler
      val timeline = timelineH()
      clearSched()
      schedNext(timeline, time)
    }
  }

  private[this] def stop()(implicit tx: S#Tx): Unit = clearSched()

  private[this] def play()(implicit tx: S#Tx): Unit = {
    val timeline  = timelineH()
    val playFrame = currentFrame()
    schedNext(timeline, playFrame)
  }

  private[this] def seek(before: Long, now: Long)(implicit tx: S#Tx): Unit = {
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
      elems.foreach(removeNode)
    }
    toAdd   .foreach { case (span, elems) =>
      elems.foreach(addNode)
    }
  }

  private[this] def clearSched()(implicit tx: S#Tx): Unit = {
    val token = tokenRef.swap(-1)
    if (token >= 0) transport.scheduler.cancel(token)
  }

  private[this] def schedNext(timeline: Timeline[S], frame: Long)(implicit tx: S#Tx): Unit = {
    timeline.eventAfter(frame).foreach { nextFrame =>
      val s         = transport.scheduler
      val schedTime = s.time
      val nextTime  = schedTime + nextFrame - frame
      val token     = s.schedule(nextTime) { implicit tx =>
        eventReached(nextFrame)
      }
      val oldToken = tokenRef.swap(token)
      s.cancel(oldToken)
    }
  }

  private[this] def eventReached(frame: Long)(implicit tx: S#Tx): Unit = {
    val timeline          = timelineH()
    frameRef()            = frame
    // println(s"frameRef = $frame")
    val (startIt, stopIt) = timeline.eventsAt(frame)
    // if (startIt.isEmpty || stopIt.isEmpty) {

    stopIt .foreach { case (_, xs) => xs.foreach(removeNode) }
    startIt.foreach { case (_, xs) => xs.foreach(addNode   ) }

    //    } else {
    //      // Here is the point where we
    //      // heuristically establish coherence.
    //      // We ask the views for `stopIt` if they can "migrate"
    //      // to any element in `startIt`.
    //      val startList = startIt.flatMap(_._2).toList
    //      val stopList  = stopIt .flatMap(_._2).toList
    //
    //      @tailrec
    //      def migrateLoop(stopRem: List[Timed[S]], startRem: List[Timed[S]]): List[Timed[S]] = stopRem match {
    //        case headStop :: tailStop =>
    //          val stopView = map.getOrElse(headStop.id, throw new NoSuchElementException(s"No view for $headStop"))
    //
    //          @tailrec
    //          def inner(startUnconsumed: List[Timed[S]], startToTry: List[Timed[S]]): List[Timed[S]] = startToTry match {
    //            case headStart :: tailStart =>
    //              if (stopView.tryMigrate(headStart.value)) {
    //                val startView = stopView
    //                // we may consume the start by migrating the stop view.
    //                // remove the stop view from the map, and associate it instead
    //                // with the start element's id
    //                map.remove(headStop.id)
    //                map.put(headStart.id, startView)
    //                // viewSet -= stopView
    //                // viewSet += startView
    //                startUnconsumed ::: tailStart
    //              } else {
    //                // can't migrate. put start element to remaining stack and loop
    //                inner(headStart :: startUnconsumed, tailStart)
    //              }
    //
    //            case Nil =>
    //              // no more start elements to check. that proceed with
    //              // removal of stop element and return not consumed start elements
    //              removeChild(headStop)
    //              startUnconsumed
    //          }
    //
    //          val startRemNext = inner(Nil, startRem)
    //          migrateLoop(tailStop, startRemNext)
    //
    //        case Nil => startRem
    //      }
    //
    //      val stillToStart = migrateLoop(stopList, startList)
    //      stillToStart.foreach(addChild)
    //    }

    schedNext(timeline, frame)
  }
}
