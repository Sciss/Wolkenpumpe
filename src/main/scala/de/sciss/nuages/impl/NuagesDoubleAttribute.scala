package de.sciss.nuages
package impl

import de.sciss.lucre.expr.DoubleObj
import de.sciss.lucre.stm.{Sys, Obj}
import de.sciss.lucre.synth.{Sys => SSys}

object NuagesDoubleAttribute extends NuagesAttribute.Factory {
  def typeID: Int = DoubleObj.typeID

  type Repr[~ <: Sys[~]] = DoubleObj[~]

  def apply[S <: SSys[S]](key: String, obj: DoubleObj[S], parent: NuagesObj[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute[S] = {
    val spec  = NuagesAttributeImpl.getSpec(parent, key)
    val value = obj.value
    new NuagesDoubleAttribute[S](parent, key = key, spec = spec, valueA = value, mapping = None).init(obj)
  }
}
final class NuagesDoubleAttribute[S <: SSys[S]](val parent: NuagesObj[S], val key: String, val spec: ParamSpec,
                                               @volatile var valueA: Double,
                                               val mapping: Option[NuagesAttribute.Mapping[S]])
  extends NuagesScalarAttribute[S] {

  type A = Double

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