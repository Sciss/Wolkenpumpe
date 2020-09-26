/*
 *  Warp.scala
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
import de.sciss.lucre.{Expr, Ident, Txn, Var => LVar}
import de.sciss.lucre.impl.ExprTypeImpl
import de.sciss.serial.{ConstFormat, DataInput, DataOutput, Writable}
import de.sciss.synth.{GE, _}

import scala.annotation.switch
import scala.math.Pi

object Warp {
  def read(in: DataInput): Warp = format.read(in)

  def init(): Unit = Obj.init()

  object Obj extends ExprTypeImpl[Warp, Obj] {
    import Warp.{Obj => Repr}

    final val typeId = 20

    implicit def valueFormat: ConstFormat[Warp] = Warp.format

    def tryParse(value: Any): Option[Warp] = value match {
      case x: Warp  => Some(x)
      case _        => None
    }

    protected def mkConst[T <: Txn[T]](id: Ident[T], value: A)(implicit tx: T): Const[T] =
      new _Const[T](id, value)

    protected def mkVar[T <: Txn[T]](targets: Targets[T], vr: LVar[T,E[T]], connect: Boolean)
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
  trait Obj[T <: Txn[T]] extends Expr[T, Warp]

  implicit object format extends ConstFormat[Warp] {
    def read(in: DataInput): Warp = {
      val id = in.readShort()
      (id: @switch) match {
        case LinearWarp     .id => LinearWarp
        case ExponentialWarp.id => ExponentialWarp
        case ParametricWarp .id => ParametricWarp(in.readDouble())
        case CosineWarp     .id => CosineWarp
        case SineWarp       .id => SineWarp
        case FaderWarp      .id => FaderWarp
        case DbFaderWarp    .id => DbFaderWarp
        case IntWarp        .id => IntWarp
      }
    }

    def write(value: Warp, out: DataOutput): Unit = value.write(out)
  }
}
trait Warp extends Writable {
  /** Maps a scalar value from normalized range to spec.
    * Note: this does not involve rounding
    * according to the spec's step parameter!
    */
  def map(spec: ParamSpec, value: Double): Double

  /** Maps a UGen signal from normalized range to spec.
    * Note: this does not involve rounding
    * according to the spec's step parameter!
    */
  def map(spec: ParamSpec, value: GE): GE

  /** Maps a scalar value from spec to normalized range */
  def inverseMap(spec: ParamSpec, value: Double): Double

  /** Maps a UGen signal from spec to normalized range */
  def inverseMap(spec: ParamSpec, value: GE): GE
}

case object LinearWarp extends Warp {
  final val id = 0

  def map       (spec: ParamSpec, value: Double): Double  = value * spec.range + spec.lo
  def map       (spec: ParamSpec, value: GE    ): GE      = value * spec.range + spec.lo

  def inverseMap(spec: ParamSpec, value: Double): Double  = (value - spec.lo) / spec.range
  def inverseMap(spec: ParamSpec, value: GE    ): GE      = (value - spec.lo) / spec.range

  def write(out: DataOutput): Unit = out.writeShort(id)
}

case object ExponentialWarp extends Warp {
  final val id = 1

  def map       (spec: ParamSpec, value: Double): Double  = spec.ratio.pow(value) * spec.lo
  def map       (spec: ParamSpec, value: GE    ): GE      = (spec.hi / spec.lo).pow(value) * spec.lo

  def inverseMap(spec: ParamSpec, value: Double): Double  = (value / spec.lo).log / spec.ratio.log
  def inverseMap(spec: ParamSpec, value: GE    ): GE      = (value / spec.lo).log / spec.ratio.log

  def write(out: DataOutput): Unit = out.writeShort(id)
}

object ParametricWarp {
  final val id = 2
}

/** Equivalent to `CurveWarp` in SuperCollider. For |curvature| < 0.001, this falls back
  * to linear mapping.
  */
final case class ParametricWarp(curvature: Double) extends Warp {
  def id: Int = ParametricWarp.id

  private[this] val useLin  = curvature.abs < 0.001
  private[this] val grow    = math.exp(curvature)

  def map(spec: ParamSpec, value: Double): Double =
    if (useLin) LinearWarp.map(spec, value) else {
      val a = spec.range / (1.0 - grow)
      val b = spec.lo + a
      b - (a * grow.pow(value))
    }

  def map(spec: ParamSpec, value: GE): GE =
    if (useLin) LinearWarp.map(spec, value) else {
      val a = spec.range / (1.0 - grow)
      val b = spec.lo + a
      b - (a * grow.pow(value))
    }

  def inverseMap(spec: ParamSpec, value: Double): Double =
    if (useLin) LinearWarp.inverseMap(spec, value) else {
      val a = spec.range / (1.0 - grow)
      val b = spec.lo + a
      ((b - value) / a).log / curvature
    }

  def inverseMap(spec: ParamSpec, value: GE): GE =
    if (useLin) LinearWarp.inverseMap(spec, value) else {
      val a = spec.range / (1.0 - grow)
      val b = spec.lo + a
      ((b - value) / a).log / curvature
    }

  def write(out: DataOutput): Unit = {
    out.writeShort(id)
    out.writeDouble(curvature)
  }
}

case object CosineWarp extends Warp {
  final val id = 3

  def map(spec: ParamSpec, value: Double): Double =
    LinearWarp.map(spec, 0.5 - ((Pi * value).cos * 0.5))

  def map(spec: ParamSpec, value: GE): GE =
    LinearWarp.map(spec, 0.5 - ((Pi * value).cos * 0.5))

  def inverseMap(spec: ParamSpec, value: Double): Double =
    (1.0 - (LinearWarp.inverseMap(spec, value) * 2.0)).acos / Pi

  def inverseMap(spec: ParamSpec, value: GE): GE =
    (1.0 - (LinearWarp.inverseMap(spec, value) * 2.0)).acos / Pi

  def write(out: DataOutput): Unit = out.writeShort(id)
}

case object SineWarp extends Warp {
  final val id = 4

  def map(spec: ParamSpec, value: Double): Double =
    LinearWarp.map(spec, (0.5 * Pi * value).sin)

  def map(spec: ParamSpec, value: GE): GE =
    LinearWarp.map(spec, (0.5 * Pi * value).sin)

  def inverseMap(spec: ParamSpec, value: Double): Double =
    LinearWarp.inverseMap(spec, value).asin / (0.5 * Pi)

  def inverseMap(spec: ParamSpec, value: GE): GE =
    LinearWarp.inverseMap(spec, value).asin / (0.5 * Pi)

  def write(out: DataOutput): Unit = out.writeShort(id)
}

case object FaderWarp extends Warp {
  final val id = 5

  def map(spec: ParamSpec, value: Double): Double = {
    val range = spec.range
    if (range >= 0)
      value.squared * range + spec.lo
    else
      (1 - (1 - value).squared) * range + spec.lo
  }

  def map(spec: ParamSpec, value: GE): GE = {
    val range = spec.range
    if (range >= 0)
      value.squared * range + spec.lo
    else
      (1 - (1 - value).squared) * range + spec.lo
  }

  def inverseMap(spec: ParamSpec, value: Double): Double = {
    val range = spec.range
    if (range >= 0)
      ((value - spec.lo) / range).sqrt
    else
      1 - (1 - ((value - spec.lo) / range)).sqrt
  }

  def inverseMap(spec: ParamSpec, value: GE): GE = {
    val range = spec.range
    if (range >= 0)
      ((value - spec.lo) / range).sqrt
    else
      1 - (1 - ((value - spec.lo) / range)).sqrt
  }

  def write(out: DataOutput): Unit = out.writeShort(id)
}

/** Equivalent to `DbFaderWarp` in SuperCollider. */
case object DbFaderWarp extends Warp {
  final val id = 6

  def map(spec: ParamSpec, value: Double): Double = {
    val loDb    = spec.lo.dbAmp
    val hiDb    = spec.hi.dbAmp
    val rangeDb = hiDb - loDb
    if (rangeDb >= 0)
      (value.squared * rangeDb + loDb).ampDb
    else
      ((1 - (1 - value).squared) * rangeDb + loDb).ampDb
  }

  def map(spec: ParamSpec, value: GE): GE  = {
    val loDb    = spec.lo.dbAmp
    val hiDb    = spec.hi.dbAmp
    val rangeDb = hiDb - loDb
    if (rangeDb >= 0)
      (value.squared * rangeDb + loDb).ampDb
    else
      ((1 - (1 - value).squared) * rangeDb + loDb).ampDb
  }

  def inverseMap(spec: ParamSpec, value: Double): Double = {
    val loDb    = spec.lo.dbAmp
    val hiDb    = spec.hi.dbAmp
    val rangeDb = hiDb - loDb
    if (spec.range >= 0)
      ((value.dbAmp - loDb) / rangeDb).sqrt
    else
      1 - (1 - ((value.dbAmp - loDb) / rangeDb)).sqrt
  }

  def inverseMap(spec: ParamSpec, value: GE): GE = {
    val loDb    = spec.lo.dbAmp
    val hiDb    = spec.hi.dbAmp
    val rangeDb = hiDb - loDb
    if (spec.range >= 0)
      ((value.dbAmp - loDb) / rangeDb).sqrt
    else
      1 - (1 - ((value.dbAmp - loDb) / rangeDb)).sqrt
  }

  def write(out: DataOutput): Unit = out.writeShort(id)
}

case object IntWarp extends Warp {
  final val id = 7

  def map       (spec: ParamSpec, value: Double): Double  = (value * spec.range + spec.lo).roundTo(1.0)
  def map       (spec: ParamSpec, value: GE    ): GE      = (value * spec.range + spec.lo).roundTo(1.0)

  def inverseMap(spec: ParamSpec, value: Double): Double  = (value - spec.lo) / spec.range
  def inverseMap(spec: ParamSpec, value: GE    ): GE      = (value - spec.lo) / spec.range

  def write(out: DataOutput): Unit = out.writeShort(id)
}
