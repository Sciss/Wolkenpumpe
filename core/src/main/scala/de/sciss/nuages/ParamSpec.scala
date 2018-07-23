/*
 *  ParamSpec.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2017 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.lucre.event.Targets
import de.sciss.lucre.expr.Expr
import de.sciss.lucre.expr.impl.ExprTypeImpl
import de.sciss.lucre.stm
import de.sciss.lucre.stm.Sys
import de.sciss.serial.{DataInput, DataOutput, ImmutableSerializer, Writable}
import de.sciss.synth

object ParamSpec {
  final val typeId = 21

  final val Key     = "spec"
  final val DashKey = "-spec"

  def composeKey(attrKey: String): String = s"$attrKey$DashKey"

  private final val COOKIE = 0x505301 // "PS\1"

  def init(): Unit = Obj.init()

  //  def apply[S <: Sys[S]](lo: Expr[S, Double], hi: Expr[S, Double], warp: Expr[S, Warp],
//                         step: Expr[S, Double], unit: Expr[S, String])(implicit tx: S#Tx): Obj[S]

  object Obj extends ExprTypeImpl[ParamSpec, Obj] {
    import ParamSpec.{Obj => Repr}

    def typeId: Int = ParamSpec.typeId

    implicit def valueSerializer: ImmutableSerializer[ParamSpec] = ParamSpec.serializer

    protected def mkConst[S <: Sys[S]](id: S#Id, value: A)(implicit tx: S#Tx): Const[S] =
      new _Const[S](id, value)

    protected def mkVar[S <: Sys[S]](targets: Targets[S], vr: S#Var[_Ex[S]], connect: Boolean)
                                    (implicit tx: S#Tx): Var[S] = {
      val res = new _Var[S](targets, vr)
      if (connect) res.connect()
      res
    }

    private[this] final class _Const[S <: Sys[S]](val id: S#Id, val constValue: A)
      extends ConstImpl[S] with Repr[S]

    private[this] final class _Var[S <: Sys[S]](val targets: Targets[S], val ref: S#Var[_Ex[S]])
      extends VarImpl[S] with Repr[S]
  }
  trait Obj[S <: Sys[S]] extends Expr[S, ParamSpec] {
//    def lo  (implicit tx: S#Tx): Expr[S, Double]
//    def hi  (implicit tx: S#Tx): Expr[S, Double]
//    def warp(implicit tx: S#Tx): Expr[S, Warp  ]
//    def unit(implicit tx: S#Tx): Expr[S, String]
  }

  implicit object serializer extends ImmutableSerializer[ParamSpec] {
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

  def read(in: DataInput): ParamSpec = serializer.read(in)

  /** A no-op now! */
  def copyAttr[S <: Sys[S]](source: stm.Obj[S], target: stm.Obj[S])(implicit tx: S#Tx): Unit = {
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

  def write(out: DataOutput): Unit = ParamSpec.serializer.write(this, out)
}