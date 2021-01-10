/*
 *  NuagesOutputAttrInput.scala
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

import de.sciss.lucre.{Disposable, Obj, Source, Txn, synth}
import de.sciss.nuages.NuagesAttribute.{Input, Parent}
import de.sciss.proc.Proc
import prefuse.data.{Node => PNode}

import scala.concurrent.stm.Ref

object NuagesOutputAttrInput extends NuagesAttribute.Factory {
  def typeId: Int = Proc.Output.typeId

  type Repr[~ <: Txn[~]] = Proc.Output[~]

  def apply[T <: synth.Txn[T]](attr: NuagesAttribute[T], parent: Parent[T], frameOffset: Long, obj: Proc.Output[T])
                         (implicit tx: T, context: NuagesContext[T]): Input[T] =
    new NuagesOutputAttrInput[T](attr, tx.newHandle(obj)).init(obj, parent)

  def tryConsume[T <: synth.Txn[T]](oldInput: Input[T], newOffset: Long, newValue: Proc.Output[T])
                              (implicit tx: T, context: NuagesContext[T]): Option[Input[T]] = None
}
final class NuagesOutputAttrInput[T <: synth.Txn[T]](val attribute: NuagesAttribute[T],
                                               objH: Source[T, Proc.Output[T]])
                                               (implicit context: NuagesContext[T])
  extends NuagesAttrSingleInput[T] with NuagesOutput.Input[T] {

  import Txn.peer

  private def deferVisTx(body: => Unit)(implicit tx: T): Unit =
    attribute.parent.main.deferVisTx(body)

  private[this] var _observer: Disposable[T] = _
  private[this] var _pNode          = Option.empty[PNode]
  private[this] val outputViewRef   = Ref(Option.empty[NuagesOutput[T]])

  override def toString = s"NuagesOutput.Input($attribute)@${hashCode.toHexString}"

  def output(implicit tx: T): Proc.Output[T] = objH()

  def tryConsume(newOffset: Long, to: Obj[T])(implicit tx: T): Boolean = false

  def input(implicit tx: T): Obj[T] = output   // yeah, it sounds odd...

  def dispose()(implicit tx: T): Unit = {
    _observer.dispose()
    unsetView()
  }

  private def init(obj: Proc.Output[T], parent: Parent[T])(implicit tx: T): this.type = {
    inputParent = parent
    _observer   = context.observeAux[NuagesOutput[T]](obj.id) { implicit tx => {
      case NuagesContext.AuxAdded  (_, view) => setView(view)
      case NuagesContext.AuxRemoved(_      ) => unsetView()
    }}
    context.getAux[NuagesOutput[T]](obj.id).foreach(setView)
    this
  }

  private def setView(view: NuagesOutput[T])(implicit tx: T): Unit = {
    val old = outputViewRef.swap(Some(view))
    require(old.isEmpty)
    view.addMapping(this)
    deferVisTx {
      require(_pNode.isEmpty)
      _pNode = Some(view.pNode)
      attribute.addPNode(view.pNode, isFree = false)
      log(s"NuagesOutput ADDED   for AttrInput: $view / $attribute")
    }
  }

  private def unsetView()(implicit tx: T): Unit = {
    outputViewRef.swap(None).foreach(_.removeMapping(this))
    deferVisTx {
      _pNode.foreach { n =>
        attribute.removePNode(n)
        _pNode = None
        log(s"NuagesOutput REMOVED for AttrInput $attribute")
      }
    }
  }
}