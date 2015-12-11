package de.sciss.nuages
package impl

import de.sciss.lucre.expr.IntObj
import de.sciss.lucre.stm.{Obj, Sys}
import de.sciss.lucre.synth.{Sys => SSys}

object NuagesIntAttrInput extends NuagesAttribute.Factory {
  def typeID: Int = IntObj.typeID

  type Repr[~ <: Sys[~]] = IntObj[~]

  def apply[S <: SSys[S]](key: String, obj: IntObj[S], attr: NuagesAttribute[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute.Input[S] = {
//    val spec  = NuagesAttributeImpl.getSpec(parent, key)
    val value = obj.value
    new NuagesIntAttrInput[S](attr, valueA = value).init(obj)
  }
}
final class NuagesIntAttrInput[S <: SSys[S]](val attribute: NuagesAttribute[S], @volatile var valueA: Int)
  extends NuagesScalarAttrInput[S] {

  type A = Int

  protected def editable: Boolean = ???

  protected def toDouble  (in: Int   ): Double = in.toDouble
  protected def fromDouble(in: Double): Int    = in.toInt

  protected def init1(obj: Obj[S])(implicit tx: S#Tx): Unit =
    obj match {
      case dObj: IntObj[S] =>
        observers ::= dObj.changed.react { implicit tx => upd =>
          updateValueAndRefresh(upd.now)
        }
      case _ =>
    }
}