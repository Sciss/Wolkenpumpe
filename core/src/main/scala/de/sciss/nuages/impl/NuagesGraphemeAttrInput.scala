/*
 *  NuagesGraphemeAttrInput.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2017 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.equal.Implicits._
import de.sciss.lucre.expr.LongObj
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, Obj, Sys, TxnLike}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.{Input, Parent}
import de.sciss.span.Span
import de.sciss.synth.proc.{Grapheme, TimeRef, Transport}

import scala.annotation.tailrec
import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref

object NuagesGraphemeAttrInput extends NuagesAttribute.Factory {
  def typeId: Int = Grapheme.typeId

  type Repr[S <: Sys[S]] = Grapheme[S]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: NuagesAttribute.Parent[S],
                          frameOffset: Long, value: Grapheme[S])
                         (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] = {
    new NuagesGraphemeAttrInput(attr, frameOffset = frameOffset).init(value, parent)
  }

  def tryConsume[S <: SSys[S]](oldInput: Input[S], _newOffset0: Long, newValue: Grapheme[S])
                              (implicit tx: S#Tx, context: NuagesContext[S]): Option[Input[S]] = {
    log(s"NuagesGraphemeAttrInput.tryConsume($oldInput, ${_newOffset0}, $newValue)")
    val attr          = oldInput.attribute
    val parent        = attr.parent
    val main          = parent.main
    val _frameOffset  = parent.frameOffset
    if (_frameOffset === Long.MaxValue) return None  // what should we do?
    val transportPos  = main.transport.position
    val currentOffset = transportPos - _frameOffset

    newValue.intersect(currentOffset) match {
      case Vec(entry) =>
        val time        = entry.key.value
        val head        = entry.value
        val _newOffset  = time + _frameOffset
        if (oldInput.tryConsume(newOffset = _newOffset, newValue = head)) {
          val res = new NuagesGraphemeAttrInput(attr, frameOffset = _frameOffset)
            .consume(time, head, oldInput, newValue, attr.inputParent)
          log(s"-> $res")
          Some(res)
        } else {
          log(s"-> $head -> None")
          val opt = NuagesAttribute.getFactory(head)
          opt.flatMap { factory =>
            factory.tryConsume(oldInput = oldInput, newOffset = parent.frameOffset,
              newValue = head.asInstanceOf[factory.Repr[S]])
          }
        }

      case _ =>
        log("-> None")
        None
    }
  }
}
final class NuagesGraphemeAttrInput[S <: SSys[S]] private(val attribute: NuagesAttribute[S],
                                                          protected val frameOffset: Long)
                                                         (implicit context: NuagesContext[S])
  extends NuagesAttrInputBase[S] with NuagesScheduledBase[S] with Parent[S] {

  import TxnLike.peer

  override def toString = s"$attribute gr[$frameOffset / ${TimeRef.framesToSecs(frameOffset)}]"

  protected var graphemeH: stm.Source[S#Tx, Grapheme[S]] = _

  def tryConsume(newOffset: Long, to: Obj[S])(implicit tx: S#Tx): Boolean = {
    log(s"$this.tryConsume($newOffset, $to) -> false")
    false
  }

  def input(implicit tx: S#Tx): Obj[S] = graphemeH()

  protected def transport: Transport[S] = attribute.parent.main.transport

  private[this] var observer: Disposable[S#Tx] = _

  private def init(gr: Grapheme[S], parent: Parent[S])(implicit tx: S#Tx): this.type = {
    log(s"$this init")
    graphemeH   = tx.newHandle(gr)
    inputParent = parent
    initPosition()
    initObserver(gr)
    initGrapheme(gr)
    initTransport()
    this
  }

  private def consume(start: Long, child: Obj[S], childView: Input[S], gr: Grapheme[S], parent: Parent[S])
                     (implicit tx: S#Tx): this.type = {
    log(s"$this consume ($start - ${TimeRef.framesToSecs(start)})")
    graphemeH             = tx.newHandle(gr)
    inputParent           = parent
    childView.inputParent = this
    initPosition()
    initObserver(gr)
    currentView()         = new View(start = start, input = childView)
    initTransport()
    this
  }

  private[this] def initGrapheme(gr: Grapheme[S])(implicit tx: S#Tx): Unit = {
    val frame0 = currentOffset()
    gr.floor(frame0).foreach { entry =>
      elemAdded(entry.key.value, entry.value)
    }
  }

  private[this] def initObserver(gr: Grapheme[S])(implicit tx: S#Tx): Unit =
    observer = gr.changed.react { implicit tx => upd =>
      if (!isDisposed) upd.changes.foreach {
        case Grapheme.Added  (time, entry) => elemAdded  (time, entry.value)
        case Grapheme.Removed(time, entry) => elemRemoved(time, entry.value)
        case Grapheme.Moved(change, entry) =>
          val t     = transport
          val time  = currentOffset()

          offsetRef() = time

          elemRemoved(change.before, entry.value)
          elemAdded  (change.now   , entry.value)

          if (t.isPlaying && {
            val from = Span.from(time)
            from.contains(change.before) || from.contains(change.now)
          }) {
            // new child might start or stop before currently
            // scheduled next event. Simply reset scheduler
            reschedule(time)
          }
      }
    }

  private[this] final class View(val start: Long, val input: NuagesAttribute.Input[S]) {
    def isEmpty  : Boolean = start === Long.MaxValue
    def isDefined: Boolean = !isEmpty

    def dispose()(implicit tx: S#Tx): Unit = if (isDefined) input.dispose()

    override def toString: String = if (isEmpty) "View(<empty>)" else s"View($start, $input)"
  }

  private[this] def emptyView   = new View(Long.MaxValue, null)
  private[this] val currentView = Ref(emptyView)

  def collect[A](pf: PartialFunction[Input[S], A])(implicit tx: S#Tx): Iterator[A] = {
    val curr = currentView()
    if (curr.isDefined) curr.input.collect(pf) else Iterator.empty
  }

  private[this] def elemAdded(start: Long, child: Obj[S])(implicit tx: S#Tx): Unit = {
    val t     = transport
    val time  = currentOffset()
    val curr  = currentView()
    val isNow = (curr.isEmpty || start > curr.start) && start <= time
    if (isNow) {
      setChild(start = start, child = child)
      offsetRef() = time
      if (t.isPlaying) reschedule(time)
    }
  }

  private[this] def elemRemoved(start: Long, child: Obj[S])(implicit tx: S#Tx): Unit = {
    val curr = currentView()
    if (curr.start === start && curr.input.input === child) {
      currentView.swap(emptyView).dispose()
    }
  }

//  private[this] def elemRemoved1(start: Long, child: Obj[S], childView: Elem)
//                                (implicit tx: S#Tx): Unit = {
//    // remove view for the element from tree and map
//    ...
//  }

  def updateChild(before: Obj[S], now: Obj[S], dt: Long, clearRight: Boolean)(implicit tx: S#Tx): Unit = {
    val gr = graphemeH()
    gr.modifiableOption.fold(updateParent(now, dt = dt, clearRight = clearRight)) { grm =>
      val curr = currentView()
      require(curr.isDefined)
      val beforeStart = curr.start
      val nowStart    = currentOffset() + dt
      log(s"$this updateChild($before - $beforeStart / ${TimeRef.framesToSecs(beforeStart)}, $now - $nowStart / ${TimeRef.framesToSecs(nowStart)})")
      if ((beforeStart !== nowStart) && isTimeline) {
        val nowStartObj = LongObj.newVar[S](nowStart)
        grm.add(nowStartObj, now)
      } else {
        val entry = grm.at(beforeStart).getOrElse(throw new IllegalStateException(s"No element at $beforeStart"))
        require(entry.value === before)
        grm.remove(entry.key, entry.value)
        grm.add   (entry.key, now)
      }

      if (clearRight && nowStart >= beforeStart && nowStart < Long.MaxValue) {
        // XXX TODO --- is there a situation where we need to ask parent to clearRight as well?

        @tailrec
        def testRemove(): Unit =
          grm.ceil(nowStart + 1) match {
            case Some(entry) =>
              grm.remove(entry.key, entry.value)
              testRemove()

            case None =>
          }

        testRemove()
      }
    }
  }

  def addChild(child: Obj[S])(implicit tx: S#Tx): Unit = {
    ???!
  }

  def removeChild(child: Obj[S])(implicit tx: S#Tx): Unit = {
    val gr = graphemeH()
    ???!
    if (isTimeline) {
      val stop    = currentOffset()
      val entries = gr.intersect(stop).filter(_.value === child)
      // AAA
      if (entries.nonEmpty) {
        val grm = gr.modifiableOption.getOrElse(???!)
        entries.foreach { entry =>
          grm.remove(entry.key, entry.value)
        }
      }

    } else {
      ???!
    }
  }

  // bubble up if grapheme is not modifiable
  private[this] def updateParent(childNow: Obj[S], dt: Long, clearRight: Boolean)(implicit tx: S#Tx): Unit =
    inputParent.updateChild(before = graphemeH(), now = childNow, dt = dt, clearRight = clearRight)

  def dispose()(implicit tx: S#Tx): Unit = {
    log(s"$this dispose")
    currentView.swap(emptyView).dispose()
    observer.dispose()
    disposeTransport()
  }

  protected def seek(before: Long, now: Long)(implicit tx: S#Tx): Unit = {
    val oldView = currentView()
    val gr      = graphemeH()
    gr.floor(now).fold[Unit] {
      if (oldView.isDefined) {
        val time0 = oldView.start
        elemRemoved(time0, ???!)
      }
    } { entry =>
      val time0 = entry.key.value
      if (oldView.isEmpty || (oldView.start !== time0)) {
        elemAdded(time0, entry.value)
      }
    }
  }

  protected def eventAfter(frame: Long)(implicit tx: S#Tx): Long =
    graphemeH().eventAfter(frame).getOrElse(Long.MaxValue)

  protected def processEvent(frame: Long)(implicit tx: S#Tx): Unit = {
    // log(s"$this processEvent($frame / ${TimeRef.framesToSecs(frame)}s)")
    val gr    = graphemeH()
    val child = gr.valueAt(frame).getOrElse(throw new IllegalStateException(s"Found no value at $frame"))
    setChild(start = frame, child = child)
  }

  private[this] def setChild(start: Long, child: Obj[S])(implicit tx: S#Tx): Unit = {
    log(s"$this setChild($start / ${TimeRef.framesToSecs(start)}, $child")
    val curr        = currentView()
    val childOffset = if (frameOffset === Long.MaxValue) Long.MaxValue else frameOffset + start

    def mkNew(): Unit = {
      log("-> mkNew")
      curr.dispose()
      val newView     = NuagesAttribute.mkInput(attribute, parent = this, frameOffset = childOffset, value = child)
      currentView()   = new View(start = start, input = newView)
    }

    if (curr.isEmpty) {
      mkNew()
    } else {
      val consumed = curr.input.tryConsume(newOffset = childOffset, newValue = child)
      if (!consumed) {
        val optFact = NuagesAttribute.getFactory(child)
        optFact.fold(mkNew()) { factory =>
          val optIn = factory.tryConsume(oldInput = curr.input, newOffset = childOffset,
            newValue = child.asInstanceOf[factory.Repr[S]])
          optIn.fold(mkNew()) { newView =>
            currentView() = new View(start = start, input = newView)
          }
        }
      }
    }
  }

  def numChildren(implicit tx: S#Tx): Int = {
    val curr = currentView()
    if (curr.isEmpty) 0 else curr.input.numChildren
  }
}