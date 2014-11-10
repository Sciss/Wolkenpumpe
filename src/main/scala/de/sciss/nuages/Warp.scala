/*
 *  Warp.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2014 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.lucre.event.Sys
import de.sciss.lucre.expr
import de.sciss.lucre.expr.Expr
import de.sciss.lucre.expr.Type.Extension1
import de.sciss.serial.{Writable, DataInput, DataOutput}
import de.sciss.synth
import de.sciss.synth.GE

import scala.annotation.switch

object Warp {
  def read(in: DataInput): Warp = Expr.readValue(in)

  object Expr extends expr.impl.ExprTypeImplA[Warp] {
    final val typeID = 20

    def readValue(in: DataInput): Warp = {
      val id = in.readShort()
      id /* : @switch */ match {
        case LinWarp.id => LinWarp
        case ExpWarp.id => ExpWarp
      }
    }

    def writeValue(value: Warp, out: DataOutput): Unit = value.write(out)
  }
}
trait Warp extends Writable {
  /** From normalized range to spec.
    * Note: this does not involve rounding
    * according to the spec's step parameter!
    */
  def map(spec: ParamSpec, value: Double): Double
  def map(spec: ParamSpec, value: GE    ): GE

  /** From spec to normalized range */
  def inverseMap(spec: ParamSpec, value: Double): Double
  def inverseMap(spec: ParamSpec, value: GE    ): GE
}

object LinWarp extends Warp {
  final val id = 0

  import synth._
  def map       (spec: ParamSpec, value: Double): Double  = value * spec.range + spec.lo
  def map       (spec: ParamSpec, value: GE    ): GE      = value * spec.range + spec.lo

  def inverseMap(spec: ParamSpec, value: Double): Double  = (value - spec.lo) / spec.range
  def inverseMap(spec: ParamSpec, value: GE    ): GE      = (value - spec.lo) / spec.range

  def write(out: DataOutput): Unit = out.writeShort(id)
}

object ExpWarp extends Warp {
  final val id = 1

  import synth._
  def map       (spec: ParamSpec, value: Double): Double  = spec.ratio.pow(value) * spec.lo
  def map       (spec: ParamSpec, value: GE    ): GE      = (spec.hi / spec.lo).pow(value) * spec.lo

  def inverseMap(spec: ParamSpec, value: Double): Double  = (value / spec.lo).log / spec.ratio.log
  def inverseMap(spec: ParamSpec, value: GE    ): GE      = (value / spec.lo).log / spec.ratio.log

  def write(out: DataOutput): Unit = out.writeShort(id)
}