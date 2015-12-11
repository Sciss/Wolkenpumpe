package de.sciss.nuages
package impl

import de.sciss.lucre.expr.BooleanObj
import de.sciss.lucre.stm.{Sys, Obj}
import de.sciss.lucre.synth.{Sys => SSys}

object NuagesBooleanAttribute extends NuagesAttribute.Factory {
  def typeID: Int = BooleanObj.typeID

  type Repr[~ <: Sys[~]] = BooleanObj[~]

  def apply[S <: SSys[S]](key: String, obj: BooleanObj[S], attr: NuagesAttribute[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute.Input[S] = {
    // val spec  = NuagesAttributeImpl.getSpec(attr.parent, key)
    val value = obj.value
    new NuagesBooleanAttribute[S](attr, valueA = value).init(obj)
  }
}
final class NuagesBooleanAttribute[S <: SSys[S]](val attribute: NuagesAttribute[S], @volatile var valueA: Boolean)
  extends NuagesScalarAttribute[S] {

  type A = Boolean

  protected def editable: Boolean = ???

  protected def toDouble  (in: Boolean): Double   = if (in) 1.0 else 0.0
  protected def fromDouble(in: Double ): Boolean  = in == 0.0

  protected def init1(obj: Obj[S])(implicit tx: S#Tx): Unit =
    obj match {
      case dObj: BooleanObj[S] =>
        observers ::= dObj.changed.react { implicit tx => upd =>
          updateValueAndRefresh(upd.now)
        }
      case _ =>
    }
}