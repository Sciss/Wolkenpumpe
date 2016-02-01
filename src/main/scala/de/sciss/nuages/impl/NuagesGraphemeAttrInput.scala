/*
 *  NuagesGraphemeAttrInput.scala
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

import de.sciss.lucre.expr.LongObj
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, Obj, Sys, TxnLike}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.{Parent, Input}
import de.sciss.span.Span
import de.sciss.synth.proc.{TimeRef, Grapheme, Transport}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref

object NuagesGraphemeAttrInput extends NuagesAttribute.Factory {
  def typeID: Int = Grapheme.typeID

  type Repr[S <: Sys[S]] = Grapheme[S]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: NuagesAttribute.Parent[S],
                          frameOffset: Long, value: Grapheme[S])
                         (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] = {
    new NuagesGraphemeAttrInput(attr, frameOffset = frameOffset).init(value, parent)
  }

  def tryConsume[S <: SSys[S]](oldInput: Input[S], /* _newOffset: Long, */ newValue: Grapheme[S])
                              (implicit tx: S#Tx, context: NuagesContext[S]): Option[Input[S]] = {
    val attr          = oldInput.attribute
    val parent        = attr.parent
    val main          = parent.main
    val _frameOffset  = parent.frameOffset
    if (_frameOffset == Long.MaxValue) return None  // what should we do?
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
          Some(res)
        } else None

      case _ => None
    }
  }
}
final class NuagesGraphemeAttrInput[S <: SSys[S]] private(val attribute: NuagesAttribute[S],
                                                          protected val frameOffset: Long)
                                                         (implicit context: NuagesContext[S])
  extends NuagesAttrInputBase[S] with NuagesScheduledBase[S] with Parent[S] {

  import TxnLike.peer

  override def toString = s"$attribute gr[$frameOffset / ${TimeRef.framesToSecs(frameOffset)}s]"

  protected var graphemeH: stm.Source[S#Tx, Grapheme[S]] = _

  def tryConsume(newOffset: Long, to: Obj[S])(implicit tx: S#Tx): Boolean = false

  def input(implicit tx: S#Tx): Obj[S] = graphemeH()

  protected def transport: Transport[S] = attribute.parent.main.transport

  private[this] var observer: Disposable[S#Tx] = _

  private def init(gr: Grapheme[S], parent: Parent[S])(implicit tx: S#Tx): this.type = {
    log(s"$this init")
    graphemeH   = tx.newHandle(gr)
    inputParent = parent
    initObserver(gr)
    initGrapheme(gr)
    initTransport()
    this
  }

  private def consume(start: Long, child: Obj[S], childView: Input[S], gr: Grapheme[S], parent: Parent[S])
                     (implicit tx: S#Tx): this.type = {
    log(s"$this consume ($start - ${TimeRef.framesToSecs(start)}s)")
    graphemeH             = tx.newHandle(gr)
    inputParent           = parent
    childView.inputParent = this
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
    def isEmpty  : Boolean = start == Long.MaxValue
    def isDefined: Boolean = !isEmpty

    def dispose()(implicit tx: S#Tx): Unit = if (isDefined) input.dispose()

    override def toString = if (isEmpty) "View(<empty>)" else s"View($start, $input)"
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
    ???!
//    views.find(_.obj() == child).foreach { view =>
//      // finding the object in the view-map implies that it
//      // is currently preparing or playing
//      logA(s"timeline - elemRemoved($start, $child)")
//      elemRemoved1(start, child, view)
//    }
  }

//  private[this] def elemRemoved1(start: Long, child: Obj[S], childView: Elem)
//                                (implicit tx: S#Tx): Unit = {
//    // remove view for the element from tree and map
//    ...
//  }

  def updateChild(before: Obj[S], now: Obj[S])(implicit tx: S#Tx): Unit = {
    val gr = graphemeH()
    gr.modifiableOption.fold(updateParent(before, now)) { grm =>
      val curr = currentView()
      require(curr.isDefined)
      val beforeStart = curr.start
      val nowStart    = currentOffset()
      // log(s"$this updateChild($before - $beforeStart / ${TimeRef.framesToSecs(beforeStart)}s, $now - $nowStart / ${TimeRef.framesToSecs(nowStart)}s)")
      if (beforeStart != nowStart && isTimeline) {
        val nowStartObj = LongObj.newVar[S](nowStart)
        grm.add(nowStartObj, now)
      } else {
        val entry = grm.at(beforeStart).getOrElse(throw new IllegalStateException(s"No element at $beforeStart"))
        require(entry.value == before)
        grm.remove(entry.key, entry.value)
        grm.add   (entry.key, now)
      }
    }
  }

  def addChild(child: Obj[S])(implicit tx: S#Tx): Unit = ???!

  def removeChild(child: Obj[S])(implicit tx: S#Tx): Unit = ???!

  // bubble up if grapheme is not modifiable
  private[this] def updateParent(childBefore: Obj[S], childNow: Obj[S])(implicit tx: S#Tx): Unit = {
    inputParent.updateChild(before = ???!, now = ???!)
  }

  def value: Vec[Double] = ???!

  def numChannels: Int = ???!

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
      if (oldView.isEmpty || oldView.start != time0) {
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
    val curr        = currentView()
    val childOffset = if (frameOffset == Long.MaxValue) Long.MaxValue else frameOffset + start
    if (curr.isEmpty || !curr.input.tryConsume(newOffset = childOffset, newValue = child)) {
      // log(s"elemAdded($start, $child); time = $time")
      curr.dispose()
      val newView     = NuagesAttribute.mkInput(attribute, parent = this, frameOffset = childOffset, value = child)
      currentView()   = new View(start = start, input = newView)
    }
  }
}