package de.sciss.nuages
package impl

import de.sciss.lucre.stm.Sys
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.{Input, Parent}

import scala.language.higherKinds

/** An attribute input view that can populate its state from another one.
  * In practise, it means that the Prefuse node is re-used, and state such
  * as `drag` and `fixed` are copied.
  */
trait PassAttrInput[S <: Sys[S]] extends NuagesAttribute.Input[S] with NuagesData[S] {
  type Repr[~ <: Sys[~]]

  def dragOption: Option[NumericAdjustment]

  def passFrom(that: PassAttrInput[S])(implicit tx: S#Tx): Unit
  def passedTo(that: PassAttrInput[S])(implicit tx: S#Tx): Unit

  def init(obj: Repr[S], parent: Parent[S])(implicit tx: S#Tx): Unit
}

trait PassAttrInputFactory extends NuagesAttribute.Factory { self =>
  /** Refines the return type to ensure we have a `PassAttrInput` */
  final def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: Parent[S], frameOffset: Long, value: Repr[S])
                              (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] = {
    val view = mkNoInit[S](attr)
    view.init(value, parent)
    view
  }

  protected type View[S <: Sys[S]] = PassAttrInput[S] { type Repr[~ <: Sys[~]] = self.Repr[~] }

  protected def mkNoInit[S <: SSys[S]](attr: NuagesAttribute[S])
                                     (implicit tx: S#Tx, context: NuagesContext[S]): View[S]

  final def tryConsume[S <: SSys[S]](oldInput: Input[S], newOffset: Long, newValue: Repr[S])
                                   (implicit tx: S#Tx, context: NuagesContext[S]): Option[Input[S]] = {
    oldInput match {
      case oldView: PassAttrInput[S] =>
        val parent  = oldInput.inputParent
        val newView = mkNoInit[S](oldInput.attribute)
        newView.passFrom(oldView)
        newView.init(newValue, parent)
        Some(newView)

      case _ => None
    }
  }
}
