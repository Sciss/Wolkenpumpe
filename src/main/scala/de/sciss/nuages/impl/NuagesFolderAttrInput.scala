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

import de.sciss.lucre.stm.{TxnLike, Disposable, Obj, Sys}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.Input
import de.sciss.synth.proc.Folder

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref

object NuagesFolderAttrInput extends NuagesAttribute.Factory {
  def typeID: Int = Folder.typeID

  type Repr[S <: Sys[S]] = Folder[S]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: NuagesAttribute.Parent[S], value: Folder[S])
                         (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] = {
    new NuagesFolderAttrInput(attr, inputParent = parent).init(value)
  }

  def tryConsume[S <: SSys[S]](oldInput: Input[S], newValue: Folder[S])
                              (implicit tx: S#Tx, context: NuagesContext[S]): Option[Input[S]] =
    if (newValue.size == 1) {
      val head = newValue.head
      if (oldInput.tryConsume(head)) {
        val attr    = oldInput.attribute
        val parent  = attr.inputParent
        val res     = new NuagesFolderAttrInput(attr, parent).consume(oldInput, newValue)
        Some(res)
      } else None
    } else None
}
final class NuagesFolderAttrInput[S <: SSys[S]] private(val attribute: NuagesAttribute[S],
                                                        val inputParent: NuagesAttribute.Parent[S])
                                                       (implicit context: NuagesContext[S])
  extends NuagesAttribute.Input[S] with NuagesAttribute.Parent[S] {

  import TxnLike.peer

  private[this] var _observer: Disposable[S#Tx] = _

  private[this] val map = Ref(Vector.empty[Input[S]])

  def tryConsume(to: Obj[S])(implicit tx: S#Tx): Boolean = false

  private def consume(in0: Input[S], folder: Folder[S])(implicit tx: S#Tx): this.type = {
    map() = Vector(in0)
    initObserver(folder)
    this
  }

  private def init(folder: Folder[S])(implicit tx: S#Tx): this.type = {
    map() = folder.iterator.map(mkChild).toVector
    initObserver(folder)
    this
  }

  private[this] def initObserver(folder: Folder[S])(implicit tx: S#Tx): Unit =
    _observer = folder.changed.react { implicit tx => upd => upd.changes.foreach {
      case Folder.Added  (idx, elem) =>
        val view = mkChild(elem)
        map.transform(_.patch(idx, view :: Nil, 0))
      case Folder.Removed(idx, elem) =>
        val view = map.getAndTransform(_.patch(idx, Nil, 1)).apply(idx)
        view.dispose()
    }}

  def updateChild(before: Obj[S], now: Obj[S])(implicit tx: S#Tx): Unit = ???!

  private[this] def mkChild(elem: Obj[S])(implicit tx: S#Tx): NuagesAttribute.Input[S] =
    NuagesAttribute.mkInput(attribute, parent = this, value = elem)

  def value: Vec[Double] = ???!

  def numChannels: Int = ???!

  def dispose()(implicit tx: S#Tx): Unit = {
    _observer.dispose()
    map.swap(Vector.empty).foreach(_.dispose())
  }
}