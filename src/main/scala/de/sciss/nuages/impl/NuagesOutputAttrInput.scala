/*
 *  NuagesOutputAttrInput.scala
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

import de.sciss.lucre.stm.{Disposable, Sys}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.synth.proc.Output
import prefuse.data.{Node => PNode}

import scala.collection.immutable.{IndexedSeq => Vec}

object NuagesOutputAttrInput extends NuagesAttribute.Factory {
  def typeID: Int = Output.typeID

  type Repr[~ <: Sys[~]] = Output[~]

  def apply[S <: SSys[S]](key: String, obj: Output[S], attr: NuagesAttribute[S])
                         (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute.Input[S] =
    new NuagesOutputAttrInput[S](attr).init(obj)
}
final class NuagesOutputAttrInput[S <: SSys[S]](val attribute: NuagesAttribute[S])(implicit context: NuagesContext[S])
  extends NuagesAttribute.Input[S] {

  import attribute.parent.main.deferVisTx

  private[this] var _observer: Disposable[S#Tx] = null
  private[this] var _pNode: PNode = null

  def dispose()(implicit tx: S#Tx): Unit = {
    if (_observer != null) _observer.dispose()
    deferVisTx {
      if (_pNode != null) {
        attribute.removePNode(this, _pNode)
        _pNode = null
      }
    }
  }

  private def init(obj: Output[S])(implicit tx: S#Tx): this.type = {
    context.getAux[NuagesOutput[S]](obj.id).fold[Unit] {
      _observer = context.observeAux(obj.id) { implicit tx => {
        case NuagesContext.AuxAdded(_, view: NuagesOutput[S]) => setView(view)
      }}
    } (setView)
    this
  }

  private[this] def setView(view: NuagesOutput[S])(implicit tx: S#Tx): Unit = deferVisTx {
    require(_pNode == null)
    _pNode = view.pNode
    attribute.addPNode(this, view.pNode, isFree = false)
    log(s"NuagesOutput for AttrInput: $view / $attribute")
  }

  def value: Vec[Double] = ???

  def numChannels: Int = ???
}