/*
 *  NuagesScheduledBase.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2019 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.lucre.stm.{Disposable, Sys, TxnLike}
import de.sciss.synth.proc.{TimeRef, Transport}

import scala.concurrent.stm.Ref

trait NuagesScheduledBase[S <: Sys[S]] {

  import TxnLike.peer

  // ---- abstract ----

  protected def transport: Transport[S]

  /** Absolute accumulative offset of object "begin" with respect to transport,
    * or `Long.MaxValue` if undefined.
    */
  protected def frameOffset: Long

  protected def seek(before: Long, now: Long)(implicit tx: S#Tx): Unit

  protected def eventAfter(offset: Long)(implicit tx: S#Tx): Long

  protected def processEvent(offset: Long)(implicit tx: S#Tx): Unit

  // ---- impl ----

  private[this] val tokenRef      = Ref(-1)

  /** Last frame offset for which view-state has been updated.
    * will be initialised in `initTransport`.
    */
  protected final val offsetRef   = Ref(0L)

  private[this] val playShiftRef  = Ref(0L)

  protected final def currentOffset()(implicit tx: S#Tx): Long =
    playShiftRef() + transport.position

  protected def disposeTransport()(implicit tx: S#Tx): Unit = {
    disposed() = true
    clearSched()
    observer.dispose()
  }

  private[this] var observer: Disposable[S#Tx] = _

  // It may happen that the transport observer is still
  // invoked after `dispose`, i.e. when `dispose` was
  // called from another transport observer. Therefore,
  // we have two strategies:
  // - remove the assertions in `removeNode` in sub-classes
  // - maintain a `disposed` state.
  // We go for this second approach at least as we develop,
  // so we keep as many checks in place as possible.
  private[this] val disposed  = Ref(false)

  final protected def isDisposed(implicit tx: S#Tx): Boolean = disposed()

  /** This must be called before `initTransport` and before `initTimeline`. */
  final protected def initPosition()(implicit tx: S#Tx): Unit = {
    setTransportPosition(transport.position)
    offsetRef() = currentOffset()
  }

  /** This must be called after `initPosition` and after `initTimeline`. */
  final protected def initTransport()(implicit tx: S#Tx): Unit = {
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

  private[this] def setTransportPosition(pos: Long)(implicit tx: S#Tx): Unit = {
    val frame0      = frameOffset
    // i.e. if object has absolute position, offsets will always
    // be transport-pos - frameOffset. If it hasn't, offsets will
    // always be transport-pos - transport-start-pos.
    val shift       = if (frame0 == Long.MaxValue) -pos else -frame0
    playShiftRef()  = shift
  }

  private[this] def stop()(implicit tx: S#Tx): Unit = clearSched()

  private[this] def play()(implicit tx: S#Tx): Unit = {
    val offset = currentOffset()
    log(s"$this play $offset / ${TimeRef.framesToSecs(offset)}")
    schedNext(offset)
  }

  protected final def reschedule(frame: Long)(implicit tx: S#Tx): Unit = {
    clearSched()
    schedNext(frame)
  }

  private[this] def clearSched()(implicit tx: S#Tx): Unit = {
    val token = tokenRef.swap(-1)
    if (token >= 0) transport.scheduler.cancel(token)
  }

  private[this] def schedNext(currentOffset: Long)(implicit tx: S#Tx): Unit = {
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

  private[this] def eventReached(offset: Long)(implicit tx: S#Tx): Unit = {
    offsetRef() = offset
    processEvent(offset)
    schedNext(offset)
  }
}