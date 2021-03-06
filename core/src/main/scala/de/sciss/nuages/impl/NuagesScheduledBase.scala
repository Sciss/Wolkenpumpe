/*
 *  NuagesScheduledBase.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2021 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.lucre.{Disposable, Txn}
import de.sciss.proc.{TimeRef, Transport}

import scala.concurrent.stm.Ref

trait NuagesScheduledBase[T <: Txn[T]] {

  import Txn.peer

  // ---- abstract ----

  protected def transport: Transport[T]

  /** Absolute accumulative offset of object "begin" with respect to transport,
    * or `Long.MaxValue` if undefined.
    */
  protected def frameOffset: Long

  protected def seek(before: Long, now: Long)(implicit tx: T): Unit

  protected def eventAfter(offset: Long)(implicit tx: T): Long

  protected def processEvent(offset: Long)(implicit tx: T): Unit

  // ---- impl ----

  private[this] val tokenRef      = Ref(-1)

  /** Last frame offset for which view-state has been updated.
    * will be initialised in `initTransport`.
    */
  protected final val offsetRef   = Ref(0L)

  private[this] val playShiftRef  = Ref(0L)

  protected final def currentOffset()(implicit tx: T): Long =
    playShiftRef() + transport.position

  protected def disposeTransport()(implicit tx: T): Unit = {
    disposed() = true
    clearSched()
    observer.dispose()
  }

  private[this] var observer: Disposable[T] = _

  // It may happen that the transport observer is still
  // invoked after `dispose`, i.e. when `dispose` was
  // called from another transport observer. Therefore,
  // we have two strategies:
  // - remove the assertions in `removeNode` in sub-classes
  // - maintain a `disposed` state.
  // We go for this second approach at least as we develop,
  // so we keep as many checks in place as possible.
  private[this] val disposed  = Ref(false)

  final protected def isDisposed(implicit tx: T): Boolean = disposed()

  /** This must be called before `initTransport` and before `initTimeline`. */
  final protected def initPosition()(implicit tx: T): Unit = {
    setTransportPosition(transport.position)
    offsetRef() = currentOffset()
  }

  /** This must be called after `initPosition` and after `initTimeline`. */
  final protected def initTransport()(implicit tx: T): Unit = {
    val t = transport
    observer = t.react { implicit tx => upd => if (!disposed()) upd match {
      case Transport.Play(_, _) => play()
      case Transport.Stop(_, _) => stop()
      case Transport.Seek(_, pos, isPlaying) =>
        if (isPlaying) stop()
        setTransportPosition(pos)
        val nowOffset = currentOffset()
        seek(before = offsetRef(), now = nowOffset)
        offsetRef() = nowOffset
        if (isPlaying) play()
      case _ =>
    }}

    if (t.isPlaying) play()
  }

  private def setTransportPosition(pos: Long)(implicit tx: T): Unit = {
    val frame0      = frameOffset
    // i.e. if object has absolute position, offsets will always
    // be transport-pos - frameOffset. If it hasn't, offsets will
    // always be transport-pos - transport-start-pos.
    val shift       = if (frame0 == Long.MaxValue) -pos else -frame0
    playShiftRef()  = shift
  }

  private def stop()(implicit tx: T): Unit = clearSched()

  private def play()(implicit tx: T): Unit = {
    val offset = currentOffset()
    log(s"$this play $offset / ${TimeRef.framesToSecs(offset)}")
    schedNext(offset)
  }

  protected final def reschedule(frame: Long)(implicit tx: T): Unit = {
    clearSched()
    schedNext(frame)
  }

  private def clearSched()(implicit tx: T): Unit = {
    val token = tokenRef.swap(-1)
    if (token >= 0) transport.scheduler.cancel(token)
  }

  private def schedNext(currentOffset: Long)(implicit tx: T): Unit = {
    val nextOffset = eventAfter(currentOffset)
    if (nextOffset == Long.MaxValue) return

    val s         = transport.scheduler
    val schedTime = s.time
    val nextTime  = schedTime + nextOffset - currentOffset
    val token     = s.schedule(nextTime) { implicit tx =>
      eventReached(nextOffset)
    }
    val oldToken = tokenRef.swap(token)
    s.cancel(oldToken)
  }

  private def eventReached(offset: Long)(implicit tx: T): Unit = {
    offsetRef() = offset
    processEvent(offset)
    schedNext(offset)
  }
}