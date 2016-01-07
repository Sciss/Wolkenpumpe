/*
 *  NuagesOutputAttrInput.scala
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
import de.sciss.lucre.stm.{Obj, Disposable, Sys}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.{Parent, Input}
import de.sciss.synth.proc.Output
import prefuse.data.{Node => PNode}

import scala.collection.immutable.{IndexedSeq => Vec}

object NuagesOutputAttrInput extends NuagesAttributeSingleFactory {
  def typeID: Int = Output.typeID

  type Repr[~ <: Sys[~]] = Output[~]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: Parent[S], obj: Output[S])
                         (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] =
    new NuagesOutputAttrInput[S](attr, tx.newHandle(obj)).init(obj, parent)
}
final class NuagesOutputAttrInput[S <: SSys[S]](val attribute: NuagesAttribute[S],
                                               objH: stm.Source[S#Tx, Output[S]])
                                               (implicit context: NuagesContext[S])
  extends NuagesAttrInputBase[S] {

  def tryConsume(to: Obj[S])(implicit tx: S#Tx): Boolean = false

  private[this] def deferVisTx(body: => Unit)(implicit tx: S#Tx): Unit =
    attribute.parent.main.deferVisTx(body)

  private[this] var _observer: Disposable[S#Tx] = _
  private[this] var _pNode: PNode = null

  def dispose()(implicit tx: S#Tx): Unit = {
    _observer.dispose()
    deferVisTx {
      if (_pNode != null) {
        attribute.removePNode(this, _pNode)
        _pNode = null
      }
    }
  }

  private def init(obj: Output[S], parent: Parent[S])(implicit tx: S#Tx): this.type = {
    inputParent = parent
    _observer   = context.observeAux[NuagesOutput[S]](obj.id) { implicit tx => {
      case NuagesContext.AuxAdded  (_, view) => setView(view)
      case NuagesContext.AuxRemoved(_      ) => unsetView()
    }}
    context.getAux[NuagesOutput[S]](obj.id).foreach(setView)
    this
  }

  private[this] def setView(view: NuagesOutput[S])(implicit tx: S#Tx): Unit = deferVisTx {
    require(_pNode == null)
    _pNode = view.pNode
    attribute.addPNode(this, view.pNode, isFree = false)
    log(s"NuagesOutput ADDED   for AttrInput: $view / $attribute")
  }

  private[this] def unsetView()(implicit tx: S#Tx): Unit = deferVisTx {
    require(_pNode != null)
    attribute.removePNode(this, _pNode)
    _pNode = null
    log(s"NuagesOutput REMOVED for AttrInput $attribute")
  }

  def value: Vec[Double] = ???!

  def numChannels: Int = ???!
}