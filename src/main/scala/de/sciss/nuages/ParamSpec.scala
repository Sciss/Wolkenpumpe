/*
 *  ParamSpec.scala
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

import de.sciss.lucre.expr.{Expr => _Expr}
import de.sciss.lucre.stm.Sys
import de.sciss.nuages.impl.{ParamSpecElemImpl => ElemImpl, ParamSpecExprImpl => ExprImpl}
import de.sciss.serial.{DataInput, DataOutput, Writable}
import de.sciss.synth

object ParamSpec {
  final val typeID = 21

  final val Key = "spec"

  private lazy val _init: Unit = {
    ExprImpl.init()
    ElemImpl.init()
  }
  private[nuages] def init(): Unit = _init

  trait ExprCompanion extends ExprLikeType[ParamSpec, Obj] {
    def apply[S <: Sys[S]](lo: _Expr[S, Double], hi: _Expr[S, Double], warp: _Expr[S, Warp],
                           step: _Expr[S, Double], unit: _Expr[S, String])(implicit tx: S#Tx): Obj[S]
  }

  final val Expr: ExprCompanion = ExprImpl

  trait Obj[S <: Sys[S]] extends _Expr[S, ParamSpec] {
    def lo  (implicit tx: S#Tx): _Expr[S, Double]
    def hi  (implicit tx: S#Tx): _Expr[S, Double]
    def warp(implicit tx: S#Tx): _Expr[S, Warp  ]
    // def step(implicit tx: S#Tx): _Expr[S, Double]
    def unit(implicit tx: S#Tx): _Expr[S, String]
  }

  def read(in: DataInput): ParamSpec = ExprImpl.readValue(in)
}
final case class ParamSpec(lo: Double = 0.0, hi: Double = 1.0, warp: Warp = LinearWarp, // step: Double = 0.0,
                           unit: String = "")
  extends Writable {

  import synth._
  def range = hi - lo
  def ratio = hi / lo

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

  def write(out: DataOutput): Unit = ExprImpl.writeValue(this, out)
}