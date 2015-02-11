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

import de.sciss.lucre.event.Sys
import de.sciss.model.Change
import de.sciss.serial.{Serializer, DataOutput, DataInput, Writable}
import de.sciss.synth
import de.sciss.lucre.expr.{Expr => _Expr}
import de.sciss.synth.proc
import impl.{ParamSpecExprImpl => ExprImpl, ParamSpecElemImpl => ElemImpl}

object ParamSpec {
  final val typeID = 21

  final val Key = "spec"

  // ---- init types ----
  ExprImpl
  ElemImpl

  trait ExprCompanion extends ExprLikeType[ParamSpec, Expr] {
    def apply[S <: Sys[S]](lo: _Expr[S, Double], hi: _Expr[S, Double], warp: _Expr[S, Warp],
                           step: _Expr[S, Double], unit: _Expr[S, String])(implicit tx: S#Tx): Expr[S]
  }

  final val Expr: ExprCompanion = ExprImpl

  trait Expr[S <: Sys[S]] extends _Expr[S, ParamSpec] {
    def lo  (implicit tx: S#Tx): _Expr[S, Double]
    def hi  (implicit tx: S#Tx): _Expr[S, Double]
    def warp(implicit tx: S#Tx): _Expr[S, Warp  ]
    def step(implicit tx: S#Tx): _Expr[S, Double]
    def unit(implicit tx: S#Tx): _Expr[S, String]
  }

  def read(in: DataInput): ParamSpec = ExprImpl.readValue(in)

  // ---- element ----

  implicit object Elem extends proc.Elem.Companion[Elem] {
    def typeID = ParamSpec.typeID

    def apply[S <: Sys[S]](peer: ParamSpec.Expr[S])(implicit tx: S#Tx): ParamSpec.Elem[S] = ElemImpl(peer)

    implicit def serializer[S <: Sys[S]]: Serializer[S#Tx, S#Acc, ParamSpec.Elem[S]] = ElemImpl.serializer
  }

  trait Elem[S <: Sys[S]] extends proc.Elem[S] {
    type Peer         = ParamSpec.Expr[S]
    type PeerUpdate   = Change[ParamSpec]

    def mkCopy()(implicit tx: S#Tx): Elem[S]
  }

  object Obj {
    def unapply[S <: Sys[S]](obj: proc.Obj[S]): Option[ParamSpec.Obj[S]] =
      if (obj.elem.isInstanceOf[ParamSpec.Elem[S]]) Some(obj.asInstanceOf[ParamSpec.Obj[S]])
      else None
  }

  type Obj[S <: Sys[S]] = proc.Obj.T[S, ParamSpec.Elem]
}
final case class ParamSpec(lo: Double = 0.0, hi: Double = 1.0, warp: Warp = LinearWarp, step: Double = 0.0,
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
    if (step <= 0.0) w else w.roundTo(step)
  }

  def inverseMap(value: Double): Double = warp.inverseMap(this, value)

  /** Maps a graph element from normalized range to spec.
    * Note: this does involve rounding
    * according to the spec's step parameter (unless step is zero).
    */
  def map(value: GE): GE = {
    val w = warp.map(this, value)
    if (step <= 0.0) w else w.roundTo(step)
  }

  def inverseMap(value: GE): GE = warp.inverseMap(this, value)

  def write(out: DataOutput): Unit = ExprImpl.writeValue(this, out)
}