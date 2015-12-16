/*
 *  NuagesTimelineAttrInput.scala
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
import de.sciss.lucre.stm.{Disposable, IdentifierMap, Obj, Sys, TxnLike}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.Input
import de.sciss.span.Span
import de.sciss.synth.proc.Timeline.Timed
import de.sciss.synth.proc.{Timeline, Transport}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.{Ref, TSet}

object NuagesTimelineAttrInput extends NuagesAttribute.Factory {
  def typeID: Int = Timeline.typeID

  type Repr[S <: Sys[S]] = Timeline[S]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], value: Timeline[S])
                         (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] = {
    val map = tx.newInMemoryIDMap[Input[S]]
    new NuagesTimelineAttrInput(attr, map).init(value)
  }
}
final class NuagesTimelineAttrInput[S <: SSys[S]] private(val attribute: NuagesAttribute[S],
                                                          map: IdentifierMap[S#ID, S#Tx, Input[S]])
                                                         (implicit context: NuagesContext[S])
  extends NuagesAttribute.Input[S] {

  import TxnLike.peer

  private[this] var _observers      = List.empty[Disposable[S#Tx]]
  private[this] val viewSet         = TSet.empty[Input[S]]
  // private[this] val parentSpanStart = Ref(0L)
  private[this] val tokenRef        = Ref(-1)

  private[this] var timelineH: stm.Source[S#Tx, Timeline[S]] = _

  // we simply don't allow that, it would be very complex
  def tryMigrate(to: Obj[S])(implicit tx: S#Tx): Boolean = false

  // N.B.: Currently AuralTimelineAttribute does not pay
  // attention to the parent object's time offset. Therefore,
  // to match with the current audio implementation, we also
  // do not take that into consideration, but might so in the future...
  private[this] def parentTimeOffset()(implicit tx: S#Tx): Long = {
    transport.position
//    val parentView  = attribute.parent
//    val spanOption  = parentView.spanOption
//    spanOption.fold(0L) { spanObj =>
//      spanObj.value match {
//        case span: Span.HasStart => transport.position - span.start
//        case _ => BiGroup.MaxCoordinate // no offset can be given - we may still have Span.All children
//      }
//    }
  }

  @inline
  private[this] def transport: Transport[S] = attribute.parent.main.transport

  private def init(timeline0: Timeline[S])(implicit tx: S#Tx): this.type = {
    timelineH = tx.newHandle(timeline0)

    {
      val time0 = parentTimeOffset()
      if (time0 != Long.MaxValue) {
        timeline0.intersect(time0).foreach { case (childSpan, childSeq) =>
          childSeq.foreach(addChild)
        }
      }
    }

    _observers ::= timeline0.changed.react { implicit tx => upd => upd.changes.foreach {
      case Timeline.Added(span, entry) =>
        val t    = transport
        val time = parentTimeOffset()
        if (span.contains(time)) addChild(entry)
        if (t.isPlaying && span.overlaps(Span.from(time))) {
          // new child might start or stop before currently
          // scheduled next event. Simply reset scheduler
          val timeline = timelineH()
          clearSched()
          schedNext(timeline, time)
        }

      case Timeline.Removed(span, entry) =>
        removeChild(entry, assertExists = false)

      case Timeline.Moved(change, entry) =>
        val t    = transport
        val time = parentTimeOffset()
        if (change.before.contains(time)) {
          removeChild(entry, assertExists = true)
        }
        if (change.now.contains(time)) {
          addChild(entry)
        }
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
    }}

    val t = transport
    _observers ::= t.react { implicit tx => {
      case Transport.Play(_, pos) => play(pos)
      case Transport.Stop(_, _  ) => stop()
      case Transport.Seek(_, pos, isPlaying) =>
        if (isPlaying) stop()
        ???!
        if (isPlaying) play(pos)
      case _ =>
    }}

    // NOTE: this is now also not needed as long
    // as we don't have relative time offset!
//    attribute.parent.spanOption.foreach { spanObj =>
//      _observers ::= spanObj.changed.react { implicit tx => upd =>
//        ...
//      }
//    }

    if (t.isPlaying) play(t.position)

    this
  }

  private[this] def stop()(implicit tx: S#Tx): Unit = {
    clearSched()
  }

  private[this] def play(pos: Long)(implicit tx: S#Tx): Unit = {
    val timeline  = timelineH()
    val playFrame = parentTimeOffset()
    schedNext(timeline, playFrame)
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
    val timeline = timelineH()
    val (startIt, stopIt) = timeline.eventsAt(frame)
    // if (startIt.isEmpty || stopIt.isEmpty) {

      stopIt .foreach { case (_, xs) => xs.foreach(removeChild(_, assertExists = true)) }
      startIt.foreach { case (_, xs) => xs.foreach(addChild) }

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

  private[this] def addChild(timed: Timed[S])(implicit tx: S#Tx): Unit = {
    val childView = NuagesAttribute.mkInput(attribute, timed.value)
    viewSet += childView
    map.put(timed.id, childView)
  }

  private[this] def removeChild(timed: Timed[S], assertExists: Boolean)(implicit tx: S#Tx): Unit =
    map.get(timed.id).fold[Unit] {
      if (assertExists) throw new IllegalStateException(s"View for $timed not found")
    } { childView =>
      val found = viewSet.remove(childView)
      assert(found)
      childView.dispose()
    }

  def value: Vec[Double] = ???!

  def numChannels: Int = ???!

  def dispose()(implicit tx: S#Tx): Unit = {
    clearSched()
    _observers.foreach(_.dispose())
    map.dispose()
    viewSet.foreach(_.dispose())
    viewSet.clear()
  }
}