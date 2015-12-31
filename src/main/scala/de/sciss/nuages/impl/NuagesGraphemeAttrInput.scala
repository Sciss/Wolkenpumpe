/*
 *  NuagesGraphemeAttrInput.scala
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

import de.sciss.lucre.expr.LongObj
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, IdentifierMap, Obj, Sys, TxnLike}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.Input
import de.sciss.span.Span
import de.sciss.synth.proc.{Grapheme, Transport}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref

object NuagesGraphemeAttrInput extends NuagesAttribute.Factory {
  def typeID: Int = Grapheme.typeID

  type Repr[S <: Sys[S]] = Grapheme[S]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: NuagesAttribute.Parent[S], value: Grapheme[S])
                         (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] = {
    val map = tx.newInMemoryIDMap[Input[S]]
    new NuagesGraphemeAttrInput(attr, parent = parent, map = map).init(value)
  }
}
final class NuagesGraphemeAttrInput[S <: SSys[S]] private(val attribute: NuagesAttribute[S],
                                                          parent: NuagesAttribute.Parent[S],
                                                          map: IdentifierMap[S#ID, S#Tx, Input[S]])
                                                         (implicit context: NuagesContext[S])
  extends NuagesAttribute.Input[S] with NuagesScheduledBase[S] with NuagesAttribute.Parent[S] {

  import TxnLike.peer

  protected var graphemeH: stm.Source[S#Tx, Grapheme[S]] = _

  // N.B.: Currently AuralGraphemeAttribute does not pay
  // attention to the parent object's time offset. Therefore,
  // to match with the current audio implementation, we also
  // do not take that into consideration, but might so in the future...
  protected def currentFrame()(implicit tx: S#Tx): Long = {
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

  protected def transport: Transport[S] = attribute.parent.main.transport

  private[this] var observer: Disposable[S#Tx] = _

  private def init(gr: Grapheme[S])(implicit tx: S#Tx): this.type = {
    log(s"$attribute grapheme init")
    graphemeH = tx.newHandle(gr)

    observer = gr.changed.react { implicit tx => upd =>
      if (!isDisposed) upd.changes.foreach {
        case Grapheme.Added  (time, entry) => elemAdded  (time, entry.value)
        case Grapheme.Removed(time, entry) => elemRemoved(time, entry.value)
        case Grapheme.Moved(change, entry) =>
          val t     = transport
          val time  = currentFrame()

          frameRef() = time

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

    val frame0 = currentFrame()
    gr.floor(frame0).foreach { entry =>
      elemAdded(entry.key.value, entry.value)
    }

    initTransport()
    this
  }

  private[this] final class View(val start: Long, val input: NuagesAttribute.Input[S]) {
    def isEmpty = start == Long.MaxValue
    def dispose()(implicit tx: S#Tx): Unit = if (!isEmpty) input.dispose()
  }

  private[this] def emptyView   = new View(Long.MaxValue, null)
  private[this] val currentView = Ref(emptyView)

  private[this] def elemAdded(start: Long, child: Obj[S])(implicit tx: S#Tx): Unit = {
    val t     = transport
    val time  = currentFrame()
    val curr  = currentView()
    val isNow = (curr.isEmpty || start > curr.start) && start <= time
    if (isNow) {
      println(s"elemAdded($start, $child); time = $time")
      val newView   = NuagesAttribute.mkInput(attribute, parent = this, value = child)
      curr.dispose()
      currentView() = new View(start = start, input = newView)
      frameRef()    = time
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
//    ???!
//  }

  private[this] def isTimeline: Boolean = attribute.parent.main.isTimeline

  def updateChild(before: Obj[S], now: Obj[S])(implicit tx: S#Tx): Unit = {
    val gr = graphemeH()
    gr.modifiableOption.fold(updateParent(before, now)) { grm =>
      val curr = currentView()
      require(!curr.isEmpty)
      val beforeStart = curr.start
      val nowStart    = currentFrame()
      println(s"updateChild($before - $beforeStart, $now - $nowStart)")
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

  // bubble up if grapheme is not modifiable
  private[this] def updateParent(childBefore: Obj[S], childNow: Obj[S])(implicit tx: S#Tx): Unit = {
    parent.updateChild(before = ???!, now = ???!)
  }

//  protected def addNode(timed: Timed[S])(implicit tx: S#Tx): Unit = {
//    log(s"$attribute grapheme addNode $timed")
//    val childView = NuagesAttribute.mkInput(attribute, parent = this, value = timed.value)
//    viewSet += childView
//    map.put(timed.id, childView)
//  }

//  protected def removeNode(timed: Timed[S])(implicit tx: S#Tx): Unit = {
//    log(s"$attribute grapheme removeNode $timed")
//    val childView = map.getOrElse(timed.id, throw new IllegalStateException(s"View for $timed not found"))
//    val found = viewSet.remove(childView)
//    assert(found)
//    childView.dispose()
//  }

  def value: Vec[Double] = ???!

  def numChannels: Int = ???!

  def dispose()(implicit tx: S#Tx): Unit = {
    log(s"$attribute grapheme dispose")
    currentView.swap(emptyView).dispose()
    observer.dispose()
    disposeTransport()
    map.dispose()
  }

  protected def seek(before: Long, now: Long)(implicit tx: S#Tx): Unit = ???!

  protected def eventAfter(frame: Long)(implicit tx: S#Tx): Long =
    graphemeH().eventAfter(frame).getOrElse(Long.MaxValue)

  protected def processEvent(frame: Long)(implicit tx: S#Tx): Unit = ???!
}