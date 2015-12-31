package de.sciss.nuages
package impl

import de.sciss.lucre.expr.{IntObj, Type}
import de.sciss.lucre.stm.Sys
import de.sciss.lucre.synth.{Sys => SSys}

object NuagesIntAttrInput extends NuagesAttribute.Factory {
  def typeID: Int = IntObj.typeID

  type Repr[~ <: Sys[~]] = IntObj[~]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: NuagesAttribute.Parent[S], obj: IntObj[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute.Input[S] = {
//    val spec  = NuagesAttributeImpl.getSpec(parent, key)
    val value = obj.value
    new NuagesIntAttrInput[S](attr, valueA = value).init(obj)
  }
}
final class NuagesIntAttrInput[S <: SSys[S]](val attribute: NuagesAttribute[S], @volatile var valueA: Int)
  extends NuagesScalarAttrInput[S] {

  type A                = Int
  type Ex[~ <: Sys[~]]  = IntObj[~]

  def tpe: Type.Expr[A, Ex] = IntObj

  protected def editable: Boolean = ???!

  protected def toDouble  (in: Int   ): Double = in.toDouble
  protected def fromDouble(in: Double): Int    = in.toInt

  protected def init1(obj: IntObj[S])(implicit tx: S#Tx): Unit =
    observers ::= obj.changed.react { implicit tx => upd =>
      updateValueAndRefresh(upd.now)
    }
}