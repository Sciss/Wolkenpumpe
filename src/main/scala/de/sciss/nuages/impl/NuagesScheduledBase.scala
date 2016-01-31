/*
 *  NuagesScheduledBase.scala
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

import de.sciss.lucre.stm.{Disposable, Sys, TxnLike}
import de.sciss.synth.proc.Transport

import scala.concurrent.stm.Ref

trait NuagesScheduledBase[S <: Sys[S]] {

  import TxnLike.peer

  // ---- abstract ----

  protected def transport: Transport[S]

  protected def currentFrame()(implicit tx: S#Tx): Long

  // protected def spanStartOption: Option[Long]

  protected def seek(before: Long, now: Long)(implicit tx: S#Tx): Unit

  protected def eventAfter(offset: Long)(implicit tx: S#Tx): Long

  protected def processEvent(offset: Long)(implicit tx: S#Tx): Unit

  // ---- impl ----

  private[this] val tokenRef  = Ref(-1)

  /** Last frame offset for which view-state has been updated.
    * will be initialised in `initTransport`.
    */
  protected final val offsetRef = Ref(0L)

  // private[this] val frameOffsetRef = Ref(0L)

  protected final def disposeTransport()(implicit tx: S#Tx): Unit = {
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

  final protected def initTransport()(implicit tx: S#Tx): Unit = {
    val t = transport
    observer = t.react { implicit tx => upd => if (!disposed()) upd match {
      case Transport.Play(_, pos) => play(pos)
      case Transport.Stop(_, _  ) => stop()
      case Transport.Seek(_, pos, isPlaying) =>
        if (isPlaying) stop()
        seek(before = offsetRef(), now = pos)
        offsetRef() = pos
        if (isPlaying) play(pos)
      case _ =>
    }}

    val frame0 = currentFrame()
    offsetRef() = frame0

    if (t.isPlaying) play(t.position)
  }

  private[this] def stop()(implicit tx: S#Tx): Unit = clearSched()

  private[this] def play(pos: Long)(implicit tx: S#Tx): Unit = {
    // spanStartOption.fold(0L)(pos - _)
    val playFrame = currentFrame()
    schedNext(playFrame)
  }

  protected final def reschedule(frame: Long)(implicit tx: S#Tx): Unit = {
    clearSched()
    schedNext(frame)
  }

  private[this] def clearSched()(implicit tx: S#Tx): Unit = {
    val token = tokenRef.swap(-1)
    if (token >= 0) transport.scheduler.cancel(token)
  }

  private[this] def schedNext(frame: Long)(implicit tx: S#Tx): Unit = {
    val nextFrame = eventAfter(frame)
    if (nextFrame == Long.MaxValue) return

    val s         = transport.scheduler
    val schedTime = s.time
    val nextTime  = schedTime + nextFrame - frame
    val token     = s.schedule(nextTime) { implicit tx =>
      eventReached(nextFrame)
    }
    val oldToken = tokenRef.swap(token)
    s.cancel(oldToken)
  }

  private[this] def eventReached(frame: Long)(implicit tx: S#Tx): Unit = {
    offsetRef() = frame
    processEvent(frame)
    schedNext(frame)
  }
}