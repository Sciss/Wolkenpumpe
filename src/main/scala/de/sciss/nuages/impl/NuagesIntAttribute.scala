package de.sciss.nuages
package impl

import de.sciss.lucre.expr.IntObj
import de.sciss.lucre.stm.Obj
import de.sciss.lucre.synth.Sys

object NuagesIntAttribute extends NuagesAttribute.Factory {
  def typeID: Int = IntObj.typeID

  type Repr[~ <: Sys[~]] = IntObj[~]

  def apply[S <: Sys[S]](key: String, obj: IntObj[S], parent: NuagesObj[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute[S] = {
    val spec  = NuagesAttributeImpl.getSpec(parent, key)
    val value = obj.value
    new NuagesIntAttribute[S](parent, key = key, spec = spec, valueA = value, mapping = None).init(obj)
  }
}
final class NuagesIntAttribute[S <: Sys[S]](val parent: NuagesObj[S], val key: String, val spec: ParamSpec,
                                               @volatile var valueA: Int,
                                               val mapping: Option[NuagesAttribute.Mapping[S]])
  extends NuagesScalarAttribute[S] {

  type A = Int

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