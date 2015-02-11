/*
 *  Warp.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2015 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.lucre.expr
import de.sciss.serial.{Writable, DataInput, DataOutput}
import de.sciss.synth
import de.sciss.synth.GE
import synth._

import scala.annotation.switch
import scala.math.Pi

object Warp {
  def read(in: DataInput): Warp = Expr.readValue(in)

  object Expr extends expr.impl.ExprTypeImplA[Warp] {
    final val typeID = 20

    def readValue(in: DataInput): Warp = {
      val id = in.readShort()
      (id: @switch) match {
        case LinearWarp     .id => LinearWarp
        case ExponentialWarp.id => ExponentialWarp
        case ParametricWarp .id => ParametricWarp(in.readDouble())
        case CosineWarp     .id => CosineWarp
        case SineWarp       .id => SineWarp
        case FaderWarp      .id => FaderWarp
        case DbFaderWarp    .id => DbFaderWarp
      }
    }

    def writeValue(value: Warp, out: DataOutput): Unit = value.write(out)
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
  def id = ParametricWarp.id

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
    val loDb    = spec.lo.dbamp
    val hiDb    = spec.hi.dbamp
    val rangeDb = hiDb - loDb
    if (rangeDb >= 0)
      (value.squared * rangeDb + loDb).ampdb
    else
      ((1 - (1 - value).squared) * rangeDb + loDb).ampdb
  }

  def map(spec: ParamSpec, value: GE): GE  = {
    val loDb    = spec.lo.dbamp
    val hiDb    = spec.hi.dbamp
    val rangeDb = hiDb - loDb
    if (rangeDb >= 0)
      (value.squared * rangeDb + loDb).ampdb
    else
      ((1 - (1 - value).squared) * rangeDb + loDb).ampdb
  }

  def inverseMap(spec: ParamSpec, value: Double): Double = {
    val loDb    = spec.lo.dbamp
    val hiDb    = spec.hi.dbamp
    val rangeDb = hiDb - loDb
    if (spec.range >= 0)
      ((value.dbamp - loDb) / rangeDb).sqrt
    else
      1 - (1 - ((value.dbamp - loDb) / rangeDb)).sqrt
  }

  def inverseMap(spec: ParamSpec, value: GE): GE = {
    val loDb    = spec.lo.dbamp
    val hiDb    = spec.hi.dbamp
    val rangeDb = hiDb - loDb
    if (spec.range >= 0)
      ((value.dbamp - loDb) / rangeDb).sqrt
    else
      1 - (1 - ((value.dbamp - loDb) / rangeDb)).sqrt
  }

  def write(out: DataOutput): Unit = out.writeShort(id)
}