/*
 *  NuagesGraphemeAttrInput.scala
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

import de.sciss.equal.Implicits._
import de.sciss.lucre.{Disposable, LongObj, Obj, Source, Txn, synth}
import de.sciss.nuages.NuagesAttribute.{Input, Parent}
import de.sciss.span.Span
import de.sciss.synth.proc.{Grapheme, TimeRef, Transport}

import scala.annotation.tailrec
import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref

object NuagesGraphemeAttrInput extends NuagesAttribute.Factory {
  def typeId: Int = Grapheme.typeId

  type Repr[T <: Txn[T]] = Grapheme[T]

  def apply[T <: synth.Txn[T]](attr: NuagesAttribute[T], parent: NuagesAttribute.Parent[T],
                          frameOffset: Long, value: Grapheme[T])
                         (implicit tx: T, context: NuagesContext[T]): Input[T] = {
    new NuagesGraphemeAttrInput(attr, frameOffset = frameOffset).init(value, parent)
  }

  def tryConsume[T <: synth.Txn[T]](oldInput: Input[T], _newOffset0: Long, newValue: Grapheme[T])
                              (implicit tx: T, context: NuagesContext[T]): Option[Input[T]] = {
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
              newValue = head.asInstanceOf[factory.Repr[T]])
          }
        }

      case _ =>
        log("-> None")
        None
    }
  }
}
final class NuagesGraphemeAttrInput[T <: synth.Txn[T]] private(val attribute: NuagesAttribute[T],
                                                          protected val frameOffset: Long)
                                                         (implicit context: NuagesContext[T])
  extends NuagesAttrInputBase[T] with NuagesScheduledBase[T] with Parent[T] {

  import Txn.peer

  override def toString = s"$attribute gr[$frameOffset / ${TimeRef.framesToSecs(frameOffset)}]"

  protected var graphemeH: Source[T, Grapheme[T]] = _

  def tryConsume(newOffset: Long, to: Obj[T])(implicit tx: T): Boolean = {
    log(s"$this.tryConsume($newOffset, $to) -> false")
    false
  }

  def input(implicit tx: T): Obj[T] = graphemeH()

  protected def transport: Transport[T] = attribute.parent.main.transport

  private[this] var observer: Disposable[T] = _

  private def init(gr: Grapheme[T], parent: Parent[T])(implicit tx: T): this.type = {
    log(s"$this init")
    graphemeH   = tx.newHandle(gr)
    inputParent = parent
    initPosition()
    initObserver(gr)
    initGrapheme(gr)
    initTransport()
    this
  }

  private def consume(start: Long, child: Obj[T], childView: Input[T], gr: Grapheme[T], parent: Parent[T])
                     (implicit tx: T): this.type = {
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

  private def initGrapheme(gr: Grapheme[T])(implicit tx: T): Unit = {
    val frame0 = currentOffset()
    gr.floor(frame0).foreach { entry =>
      elemAdded(entry.key.value, entry.value)
    }
  }

  private def initObserver(gr: Grapheme[T])(implicit tx: T): Unit =
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

  private[this] final class View(val start: Long, val input: NuagesAttribute.Input[T]) {
    def isEmpty  : Boolean = start === Long.MaxValue
    def isDefined: Boolean = !isEmpty

    def dispose()(implicit tx: T): Unit = if (isDefined) input.dispose()

    override def toString: String = if (isEmpty) "View(<empty>)" else s"View($start, $input)"
  }

  private def emptyView   = new View(Long.MaxValue, null)
  private[this] val currentView = Ref(emptyView)

  def collect[A](pf: PartialFunction[Input[T], A])(implicit tx: T): Iterator[A] = {
    val curr = currentView()
    if (curr.isDefined) curr.input.collect(pf) else Iterator.empty
  }

  private def elemAdded(start: Long, child: Obj[T])(implicit tx: T): Unit = {
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

  private def elemRemoved(start: Long, child: Obj[T])(implicit tx: T): Unit = {
    val curr = currentView()
    if (curr.start === start && curr.input.input === child) {
      currentView.swap(emptyView).dispose()
    }
  }

//  private def elemRemoved1(start: Long, child: Obj[T], childView: Elem)
//                                (implicit tx: T): Unit = {
//    // remove view for the element from tree and map
//    ...
//  }

  def updateChild(before: Obj[T], now: Obj[T], dt: Long, clearRight: Boolean)(implicit tx: T): Unit = {
    val gr = graphemeH()
    gr.modifiableOption.fold(updateParent(now, dt = dt, clearRight = clearRight)) { grm =>
      val curr = currentView()
      require(curr.isDefined)
      val beforeStart = curr.start
      val nowStart    = currentOffset() + dt
      log(s"$this updateChild($before - $beforeStart / ${TimeRef.framesToSecs(beforeStart)}, $now - $nowStart / ${TimeRef.framesToSecs(nowStart)})")
      if ((beforeStart !== nowStart) && isTimeline) {
        val nowStartObj = LongObj.newVar[T](nowStart)
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

  def addChild(child: Obj[T])(implicit tx: T): Unit = {
    ???!
  }

  def removeChild(child: Obj[T])(implicit tx: T): Unit = {
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
  private def updateParent(childNow: Obj[T], dt: Long, clearRight: Boolean)(implicit tx: T): Unit =
    inputParent.updateChild(before = graphemeH(), now = childNow, dt = dt, clearRight = clearRight)

  def dispose()(implicit tx: T): Unit = {
    log(s"$this dispose")
    currentView.swap(emptyView).dispose()
    observer.dispose()
    disposeTransport()
  }

  protected def seek(before: Long, now: Long)(implicit tx: T): Unit = {
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

  protected def eventAfter(frame: Long)(implicit tx: T): Long =
    graphemeH().eventAfter(frame).getOrElse(Long.MaxValue)

  protected def processEvent(frame: Long)(implicit tx: T): Unit = {
    // log(s"$this processEvent($frame / ${TimeRef.framesToSecs(frame)}s)")
    val gr    = graphemeH()
    val child = gr.valueAt(frame).getOrElse(throw new IllegalStateException(s"Found no value at $frame"))
    setChild(start = frame, child = child)
  }

  private def setChild(start: Long, child: Obj[T])(implicit tx: T): Unit = {
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
            newValue = child.asInstanceOf[factory.Repr[T]])
          optIn.fold(mkNew()) { newView =>
            currentView() = new View(start = start, input = newView)
          }
        }
      }
    }
  }

  def numChildren(implicit tx: T): Int = {
    val curr = currentView()
    if (curr.isEmpty) 0 else curr.input.numChildren
  }
}