/*
 *  NuagesFolderAttrInput.scala
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

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{TxnLike, Disposable, Obj, Sys}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.{Parent, Input}
import de.sciss.synth.proc.Folder

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref

object NuagesFolderAttrInput extends NuagesAttribute.Factory {
  def typeID: Int = Folder.typeID

  type Repr[S <: Sys[S]] = Folder[S]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: Parent[S], frameOffset: Long, value: Folder[S])
                         (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] = {
    new NuagesFolderAttrInput(attr, frameOffset = frameOffset).init(value, parent)
  }

  def tryConsume[S <: SSys[S]](oldInput: Input[S], /* newOffset: Long, */ newValue: Folder[S])
                              (implicit tx: S#Tx, context: NuagesContext[S]): Option[Input[S]] =
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
final class NuagesFolderAttrInput[S <: SSys[S]] private(val attribute: NuagesAttribute[S],
                                                        frameOffset: Long)
                                                       (implicit context: NuagesContext[S])
  extends NuagesAttrInputBase[S] with NuagesAttribute.Parent[S] {

  import TxnLike.peer

  private[this] var _observer: Disposable[S#Tx] = _

  private[this] val map = Ref(Vector.empty[Input[S]])

  private[this] var objH: stm.Source[S#Tx, Folder[S]] = _

  def tryConsume(newOffset: Long, to: Obj[S])(implicit tx: S#Tx): Boolean = false

  def input(implicit tx: S#Tx): Obj[S] = objH()

  private def consume(childView: Input[S], folder: Folder[S], parent: Parent[S])(implicit tx: S#Tx): this.type = {
    map()                 = Vector(childView)
    childView.inputParent = this
    initObserver(folder)
    this
  }

  private def init(folder: Folder[S], parent: Parent[S])(implicit tx: S#Tx): this.type = {
    inputParent = parent
    map()       = folder.iterator.map(mkChild).toVector
    initObserver(folder)
    this
  }

  private[this] def initObserver(folder: Folder[S])(implicit tx: S#Tx): Unit = {
    objH = tx.newHandle(folder)
    _observer = folder.changed.react { implicit tx => upd => upd.changes.foreach {
      case Folder.Added(idx, elem) =>
        val view = mkChild(elem)
        map.transform(_.patch(idx, view :: Nil, 0))
      case Folder.Removed(idx, elem) =>
        val view = map.getAndTransform(_.patch(idx, Nil, 1)).apply(idx)
        view.dispose()
    }}
  }

  def updateChild(before: Obj[S], now: Obj[S])(implicit tx: S#Tx): Unit = ???!

  def addChild(child: Obj[S])(implicit tx: S#Tx): Unit = {
    val folder = objH()
    if (isTimeline) {
      ???!
    } else {
      folder.addLast(child)
    }
  }

  def removeChild(child: Obj[S])(implicit tx: S#Tx): Unit = {
    val folder = objH()
    if (isTimeline) {
      ???!
    } else {
      val res = folder.remove(child)
      require(res)
    }
  }

  def numChildren(implicit tx: S#Tx): Int = collect { case x => x.numChildren } .sum

  def collect[A](pf: PartialFunction[Input[S], A])(implicit tx: S#Tx): Iterator[A] =
    map().iterator.flatMap(_.collect(pf))

  private[this] def mkChild(elem: Obj[S])(implicit tx: S#Tx): NuagesAttribute.Input[S] =
    NuagesAttribute.mkInput(attribute, value = elem, frameOffset = Long.MaxValue, parent = this)

//  def value: Vec[Double] = ...
//
//  def numChannels: Int = ...

  def dispose()(implicit tx: S#Tx): Unit = {
    _observer.dispose()
    map.swap(Vector.empty).foreach(_.dispose())
  }
}