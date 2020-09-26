/*
 *  PassAttrInput.scala
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

import de.sciss.lucre.Txn
import de.sciss.lucre.synth
import de.sciss.nuages.NuagesAttribute.{Input, Parent}
import prefuse.data.{Node => PNode}

/** An attribute input view that can populate its state from another one.
  * In practise, it means that the Prefuse node is re-used, and state such
  * as `drag` and `fixed` are copied.
  */
trait PassAttrInput[T <: Txn[T]] extends NuagesAttribute.Input[T] with NuagesData[T] {
  type Repr[~ <: Txn[~]]

  def dragOption: Option[NumericAdjustment]

  def pNode: PNode

  def passFrom(that: PassAttrInput[T])(implicit tx: T): Unit
  def passedTo(that: PassAttrInput[T])(implicit tx: T): Unit

  def init(obj: Repr[T], parent: Parent[T])(implicit tx: T): Unit
}

trait PassAttrInputFactory extends NuagesAttribute.Factory { self =>
  /** Refines the return type to ensure we have a `PassAttrInput` */
  final def apply[T <: synth.Txn[T]](attr: NuagesAttribute[T], parent: Parent[T], frameOffset: Long, value: Repr[T])
                              (implicit tx: T, context: NuagesContext[T]): Input[T] = {
    val view = mkNoInit[T](attr)
    view.init(value, parent)
    view
  }

  protected type View[T <: Txn[T]] = PassAttrInput[T] { type Repr[~ <: Txn[~]] = self.Repr[~] }

  protected def mkNoInit[T <: synth.Txn[T]](attr: NuagesAttribute[T])
                                     (implicit tx: T, context: NuagesContext[T]): View[T]

  final def tryConsume[T <: synth.Txn[T]](oldInput: Input[T], newOffset: Long, newValue: Repr[T])
                                   (implicit tx: T, context: NuagesContext[T]): Option[Input[T]] = {
    oldInput match {
      case oldView: PassAttrInput[T] =>
        val parent  = oldInput.inputParent
        val newView = mkNoInit[T](oldInput.attribute)
        newView.passFrom(oldView)
        newView.init(newValue, parent)
        Some(newView)

      case _ => None
    }
  }
}
