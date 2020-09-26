/*
 *  ParamSpec.scala
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

import de.sciss.lucre.Event.Targets
import de.sciss.lucre.{Expr, Ident, Txn, Var => LVar, Obj => LObj}
import de.sciss.lucre.impl.ExprTypeImpl
import de.sciss.serial.{ConstFormat, DataInput, DataOutput, Writable}
import de.sciss.synth

object ParamSpec {
  final val typeId = 21

  final val Key     = "spec"
  final val DashKey = "-spec"

  def composeKey(attrKey: String): String = s"$attrKey$DashKey"

  private final val COOKIE = 0x505301 // "PS\1"

  def init(): Unit = Obj.init()

  //  def apply[T <: Txn[T]](lo: Expr[T, Double], hi: Expr[T, Double], warp: Expr[T, Warp],
//                         step: Expr[T, Double], unit: Expr[T, String])(implicit tx: T): Obj[T]

  object Obj extends ExprTypeImpl[ParamSpec, Obj] {
    import ParamSpec.{Obj => Repr}

    def typeId: Int = ParamSpec.typeId

    implicit def valueFormat: ConstFormat[ParamSpec] = ParamSpec.format

    def tryParse(value: Any): Option[ParamSpec] = value match {
      case x: ParamSpec => Some(x)
      case _            => None
    }

    protected def mkConst[T <: Txn[T]](id: Ident[T], value: A)(implicit tx: T): Const[T] =
      new _Const[T](id, value)

    protected def mkVar[T <: Txn[T]](targets: Targets[T], vr: LVar[T, E[T]], connect: Boolean)
                                    (implicit tx: T): Var[T] = {
      val res = new _Var[T](targets, vr)
      if (connect) res.connect()
      res
    }

    private[this] final class _Const[T <: Txn[T]](val id: Ident[T], val constValue: A)
      extends ConstImpl[T] with Repr[T]

    private[this] final class _Var[T <: Txn[T]](val targets: Targets[T], val ref: LVar[T, E[T]])
      extends VarImpl[T] with Repr[T]
  }
  trait Obj[T <: Txn[T]] extends Expr[T, ParamSpec] {
//    def lo  (implicit tx: T): Expr[T, Double]
//    def hi  (implicit tx: T): Expr[T, Double]
//    def warp(implicit tx: T): Expr[T, Warp  ]
//    def unit(implicit tx: T): Expr[T, String]
  }

  implicit object format extends ConstFormat[ParamSpec] {
    def write(v: ParamSpec, out: DataOutput): Unit = {
      import v._
      out.writeInt(ParamSpec.COOKIE)
      out.writeDouble(lo)
      out.writeDouble(hi)
      warp.write(out)
      out.writeUTF(unit)
    }

    def read(in: DataInput): ParamSpec = {
      val cookie = in.readInt()
      if (cookie != COOKIE) sys.error(s"Unexpected cookie (found $cookie, expected $COOKIE)")

      val lo    = in.readDouble()
      val hi    = in.readDouble()
      val warp  = Warp.read(in)
      val unit  = in.readUTF()
      ParamSpec(lo = lo, hi = hi, warp = warp, /* step = step, */ unit = unit)
    }
  }

  def read(in: DataInput): ParamSpec = format.read(in)

  /** A no-op now! */
  def copyAttr[T <: Txn[T]](source: LObj[T], target: LObj[T])(implicit tx: T): Unit = {
//    val a = source.attr
//    val b = target.attr
//
//    a.get(Key).foreach { spec =>
//      b.put(Key, spec)
//    }
  }
}
final case class ParamSpec(lo: Double = 0.0, hi: Double = 1.0, warp: Warp = LinearWarp, // step: Double = 0.0,
                           unit: String = "")
  extends Writable {

  import synth._

  def range: Double = hi - lo
  def ratio: Double = hi / lo

  def clip(value: Double): Double = math.max(lo, math.min(hi, value))

  /** Maps a number from normalized range to spec.
    * Note: this does involve rounding
    * according to the spec's step parameter (unless step is zero).
    */
  def map(value: Double): Double = {
    val w = warp.map(this, value)
    w // if (step <= 0.0) w else w.roundTo(step)
  }

  /** Maps a number from spec spaced to normalized (0 ... 1) space. */
  def inverseMap(value: Double): Double = warp.inverseMap(this, value)

  /** Maps a graph element from normalized range to spec.
    * Note: this does involve rounding
    * according to the spec's step parameter (unless step is zero).
    */
  def map(value: GE): GE = {
    val w = warp.map(this, value)
    w // if (step <= 0.0) w else w.roundTo(step)
  }

  /** Maps a graph element from spec spaced to normalized (0 ... 1) space. */
  def inverseMap(value: GE): GE = warp.inverseMap(this, value)

  def write(out: DataOutput): Unit = ParamSpec.format.write(this, out)
}