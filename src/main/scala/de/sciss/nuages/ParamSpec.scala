/*
 *  ParamSpec.scala
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

import de.sciss.lucre.{event => evt, expr}
import de.sciss.lucre.event.{EventLike, InMemory, Node, Targets, Sys}
import de.sciss.model.Change
import de.sciss.serial.{DataOutput, DataInput, Writable}
import de.sciss.synth
import de.sciss.lucre.expr.{Expr => EExpr, Double => DoubleEx, String => StringEx}

object ParamSpec {
  object Expr extends ExprLikeType[ParamSpec, ParamSpec.Expr] {
    final val typeID = 21

    private final val COOKIE = 0x505300 // "PS\0"

    def readValue(in: DataInput): ParamSpec = {
      val cookie = in.readInt()
      if (cookie != COOKIE) sys.error(s"Unexpected cookie (found $cookie, expected $COOKIE)")

      val lo    = in.readDouble()
      val hi    = in.readDouble()
      val warp  = Warp.read(in)
      val step  = in.readDouble()
      val unit  = in.readUTF()
      ParamSpec(lo = lo, hi = hi, warp = warp, step = step, unit = unit)
    }

    def writeValue(value: ParamSpec, out: DataOutput): Unit = {
      out.writeInt(COOKIE)
      import value._
      out.writeDouble(lo)
      out.writeDouble(hi)
      warp.write(out)
      out.writeDouble(step)
      out.writeUTF(unit)
    }

    def readConst[S <: Sys[S]](in: DataInput): Repr[S] with EExpr.Const[S, ParamSpec] = {
      val cookie = in.readByte()
      if (cookie != 3) sys.error(s"Unexpected cookie $cookie")
      newConst[S](readValue(in))
    }

    def newConst[S <: Sys[S]](value: ParamSpec): Repr[S] with EExpr.Const[S, ParamSpec] =
      Const[S](value)

    def registerExtension(ext: expr.Type.Extension1[Repr]): Unit = ???

    def read[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): Repr[S] = serializer[S].read(in, access)

    def apply[S <: Sys[S]](lo: EExpr[S, Double], hi: EExpr[S, Double], warp: EExpr[S, Warp],
                           step: EExpr[S, Double], unit: EExpr[S, String])(implicit tx: S#Tx): Repr[S] =
      (lo, hi, warp, step, unit) match {
        case (EExpr.Const(loC), EExpr.Const(hiC), EExpr.Const(warpC), EExpr.Const(stepC), EExpr.Const(unitC)) =>
          val v = ParamSpec(lo = loC, hi = hiC, warp = warpC, step = stepC, unit = unitC)
          newConst[S](v)
        case _ =>
          val targets = evt.Targets[S]
          new Apply[S](targets, lo = lo, hi = hi, warp = warp, step = step, unit = unit)
      }
    
    implicit def serializer[S <: Sys[S]]: evt.Serializer[S, Repr[S]] = anySer.asInstanceOf[Ser[S]]

    private[this] final val anySer = new Ser[InMemory]

    private[this] final class Ser[S <: Sys[S]] extends evt.EventLikeSerializer[S, Repr[S]] {
      def readConstant(in: DataInput)(implicit tx: S#Tx): Repr[S] = Expr.readConst(in)

      def read(in: DataInput, access: S#Acc, targets: evt.Targets[S])(implicit tx: S#Tx): Repr[S] with evt.Node[S] = {
        // 0 = var, 1 = op
        in.readByte() match {
          case 0      => readVar (in, access, targets)
          case cookie => readNode(cookie, in, access, targets)
        }
      }
    }

    private[this] def readVar[S <: Sys[S]](in: DataInput, access: S#Acc, targets: evt.Targets[S])
                                          (implicit tx: S#Tx): Repr[S] with evt.Node[S] = {
      ???
    }

    private[this] def readNode[S <: Sys[S]](cookie: Int, in: DataInput, access: S#Acc, targets: evt.Targets[S])
                                           (implicit tx: S#Tx): Repr[S] with evt.Node[S] = {
      ???
    }

    private[this] final case class Const[S <: Sys[S]](constValue: ParamSpec)
      extends Repr[S] with expr.impl.ConstImpl[S, ParamSpec] {

      def lo  : EExpr[S, Double] = DoubleEx .newConst(constValue.lo  )
      def hi  : EExpr[S, Double] = DoubleEx .newConst(constValue.hi  )
      def warp: EExpr[S, Warp  ] = Warp.Expr.newConst(constValue.warp)
      def step: EExpr[S, Double] = DoubleEx .newConst(constValue.step)
      def unit: EExpr[S, String] = StringEx .newConst(constValue.unit)

      protected def writeData(out: DataOutput): Unit = writeValue(constValue, out)
    }

    private[this] final class Apply[S <: Sys[S]](protected val targets: evt.Targets[S],
                                                 val lo: EExpr[S, Double], val hi: EExpr[S, Double],
                                                 val warp: EExpr[S, Warp], val step: EExpr[S, Double],
                                                 val unit: EExpr[S, String])
      extends Repr[S]
      with evt.impl.StandaloneLike  [S, Change[ParamSpec], Repr[S]]
      with evt.impl.EventImpl       [S, Change[ParamSpec], Repr[S]]
      /* with evt.impl.MappingGenerator[S, Change[ParamSpec], Repr[S]] */ {

      protected def writeData(out: DataOutput): Unit = {
        lo  .write(out)
        hi  .write(out)
        warp.write(out)
        step.write(out)
        unit.write(out)
      }

      protected def disposeData()(implicit tx: S#Tx): Unit = ()

      def value(implicit tx: S#Tx): ParamSpec = {
        val loC   = lo.value
        val hiC   = hi.value
        val warpC = warp.value
        val stepC = step.value
        val unitC = unit.value
        ParamSpec(lo = loC, hi = hiC, warp = warpC, step = stepC, unit = unitC)
      }

      // ---- event ----

      def connect   ()(implicit tx: S#Tx): Unit = {
        lo  .changed ---> this
        hi  .changed ---> this
        warp.changed ---> this
        step.changed ---> this
        unit.changed ---> this
      }

      def disconnect()(implicit tx: S#Tx): Unit = {
        lo  .changed -/-> this
        hi  .changed -/-> this
        warp.changed -/-> this
        step.changed -/-> this
        unit.changed -/-> this
      }

      def pullUpdate(pull: evt.Pull[S])(implicit tx: S#Tx): Option[Change[ParamSpec]] = ???

      def reader: evt.Reader[S, Repr[S]] = Expr.serializer[S]

      def changed: EventLike[S, Change[ParamSpec]] = this
    }
  }

  trait Expr[S <: Sys[S]] extends EExpr[S, ParamSpec] {
    def lo  : EExpr[S, Double]
    def hi  : EExpr[S, Double]
    def warp: EExpr[S, Warp  ]
    def step: EExpr[S, Double]
    def unit: EExpr[S, String]
  }

  def read(in: DataInput): ParamSpec = Expr.readValue(in)
}
final case class ParamSpec(lo: Double = 0.0, hi: Double = 1.0, warp: Warp = LinWarp, step: Double = 0.0,
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

  def write(out: DataOutput): Unit = ParamSpec.Expr.writeValue(this, out)
}
