package de.sciss.nuages
package impl

import de.sciss.lucre.expr.DoubleObj
import de.sciss.lucre.stm.{Sys, Obj}
import de.sciss.lucre.synth.{Sys => SSys}

object NuagesDoubleAttribute extends NuagesAttribute.Factory {
  def typeID: Int = DoubleObj.typeID

  type Repr[~ <: Sys[~]] = DoubleObj[~]

  def apply[S <: SSys[S]](key: String, obj: DoubleObj[S], attr: NuagesAttribute[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute.Input[S] = {
//    val spec  = NuagesAttributeImpl.getSpec(parent, key)
    val value = obj.value
    new NuagesDoubleAttribute[S](attr, valueA = value).init(obj)
  }
}
final class NuagesDoubleAttribute[S <: SSys[S]](val attribute: NuagesAttribute[S], @volatile var valueA: Double)
  extends NuagesScalarAttribute[S] {

  type A = Double

  protected def editable: Boolean = ???

  protected def toDouble  (in: Double): Double = in
  protected def fromDouble(in: Double): Double = in

  protected def init1(obj: Obj[S])(implicit tx: S#Tx): Unit =
    obj match {
      case dObj: DoubleObj[S] =>
        observers ::= dObj.changed.react { implicit tx => upd =>
          updateValueAndRefresh(upd.now)
        }
      case _ =>
    }
}