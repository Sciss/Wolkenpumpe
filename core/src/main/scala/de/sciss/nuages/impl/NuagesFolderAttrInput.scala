/*
 *  NuagesFolderAttrInput.scala
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

import de.sciss.lucre.{Disposable, Folder, Obj, Source, Txn, synth}
import de.sciss.nuages.NuagesAttribute.{Input, Parent}
import de.sciss.proc.TimeRef

import scala.concurrent.stm.Ref

object NuagesFolderAttrInput extends NuagesAttribute.Factory {
  def typeId: Int = Folder.typeId

  type Repr[T <: Txn[T]] = Folder[T]

  def apply[T <: synth.Txn[T]](attr: NuagesAttribute[T], parent: Parent[T], frameOffset: Long, value: Folder[T])
                         (implicit tx: T, context: NuagesContext[T]): Input[T] = {
    new NuagesFolderAttrInput(attr, frameOffset = frameOffset).init(value, parent)
  }

  def tryConsume[T <: synth.Txn[T]](oldInput: Input[T], newOffset: Long, newValue: Folder[T])
                              (implicit tx: T, context: NuagesContext[T]): Option[Input[T]] =
    if (newValue.size == 1) {
      val head = newValue.head
      if (oldInput.tryConsume(newOffset = ???! /* newOffset */, newValue = head)) {
        val attr    = oldInput.attribute
        val parent  = attr.inputParent
        val res     = new NuagesFolderAttrInput(attr, frameOffset = ???! /* newOffset */).consume(oldInput, newValue, parent)
        Some(res)
      } else None
    } else None
}
final class NuagesFolderAttrInput[T <: synth.Txn[T]] private(val attribute: NuagesAttribute[T],
                                                        frameOffset: Long)
                                                       (implicit context: NuagesContext[T])
  extends NuagesAttrInputBase[T] with NuagesAttribute.Parent[T] {

  import Txn.peer

  override def toString = s"$attribute fl[$frameOffset / ${TimeRef.framesToSecs(frameOffset)}]"

  private[this] var _observer: Disposable[T] = _

  private[this] val map = Ref(Vector.empty[Input[T]])

  private[this] var objH: Source[T, Folder[T]] = _

  def tryConsume(newOffset: Long, to: Obj[T])(implicit tx: T): Boolean = false

  def input(implicit tx: T): Obj[T] = objH()

  private def consume(childView: Input[T], folder: Folder[T], parent: Parent[T])(implicit tx: T): this.type = {
    map()                 = Vector(childView)
    childView.inputParent = this
    initObserver(folder)
    this
  }

  private def init(folder: Folder[T], parent: Parent[T])(implicit tx: T): this.type = {
    inputParent = parent
    map()       = folder.iterator.map(mkChild).toVector
    initObserver(folder)
    this
  }

  private def initObserver(folder: Folder[T])(implicit tx: T): Unit = {
    objH = tx.newHandle(folder)
    _observer = folder.changed.react { implicit tx => upd => upd.changes.foreach {
      case Folder.Added(idx, elem) =>
        val view = mkChild(elem)
        map.transform(_.patch(idx, view :: Nil, 0))
      case Folder.Removed(idx, _ /* elem */) =>
        val view = map.getAndTransform(_.patch(idx, Nil, 1)).apply(idx)
        view.dispose()
    }}
  }

  def updateChild(before: Obj[T], now: Obj[T], dt: Long, clearRight: Boolean)(implicit tx: T): Unit = {
    ???!
    val folder = objH()
    if (isTimeline) {
      ???!
    } else {
      folder.remove (before)
      folder.addLast(now)
    }
  }

  def addChild(child: Obj[T])(implicit tx: T): Unit = {
    val folder = objH()
    if (isTimeline) {
      ???!
    } else {
      folder.addLast(child)
    }
  }

  def removeChild(child: Obj[T])(implicit tx: T): Unit = {
    val folder = objH()
    if (isTimeline) {
      ???!
    } else {
      val res = folder.remove(child)
      require(res)
    }
  }

  def numChildren(implicit tx: T): Int = collect { case x => x.numChildren } .sum

  def collect[A](pf: PartialFunction[Input[T], A])(implicit tx: T): Iterator[A] =
    map().iterator.flatMap(_.collect(pf))

  private def mkChild(elem: Obj[T])(implicit tx: T): NuagesAttribute.Input[T] =
    NuagesAttribute.mkInput(attribute, value = elem, frameOffset = Long.MaxValue, parent = this)

//  def value: Vec[Double] = ...
//
//  def numChannels: Int = ...

  def dispose()(implicit tx: T): Unit = {
    _observer.dispose()
    map.swap(Vector.empty).foreach(_.dispose())
  }
}