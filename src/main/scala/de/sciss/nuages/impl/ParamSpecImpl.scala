/*
 *  ParamSpecImpl.scala
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
package impl

import de.sciss.lucre.expr.{Double => DoubleEx, String => StringEx, Type1, ExprType1, Type, Expr}
import de.sciss.lucre.expr.impl.TypeImplLike
import de.sciss.lucre.{event => evt, stm, expr}
import de.sciss.lucre.event.{Event, EventLike, InMemory, Sys}
import de.sciss.model.Change
import de.sciss.nuages
import de.sciss.serial.{DataOutput, DataInput}
import de.sciss.synth.proc
import de.sciss.synth.proc.Elem

import scala.annotation.switch

// welcome to hell
object ParamSpecExprImpl
  extends ParamSpec.ExprCompanion
  // ---- yeah, fuck you Scala, we cannot implement TypeImpl1 because of initializer problems ----
  // with TypeImpl1[ParamSpec.Expr]
  with Type1[ParamSpec.Expr]
  with TypeImplLike[Type.Extension1[ParamSpec.Expr]] {

  def typeID = ParamSpec.typeID

  private[this] final val COOKIE = 0x505301 // "PS\1"

  // ---- yeah, fuck you Scala, we cannot implement TypeImpl1 because of initializer problems ----
  final protected val extTag = reflect.classTag[Type.Extension1[Repr]]
  private[this] type Ext = Type.Extension1[Repr]
  private[this] var exts = new Array[Ext](0)

  private lazy val _init: Unit = {
    DoubleEx .registerExtension(1, DoubleExtensions)
    StringEx .registerExtension(1, StringExtensions)
    Warp.Expr.registerExtension(1, WarpExtensions)
    this     .registerExtension(Apply)
  }
  def init(): Unit = _init

  final def registerExtension(ext: Ext): Unit = exts = addExtension(exts, ext)

  final protected def findExt(op: Int): Ext = findExt(exts, op)

  protected def readExtension[S <: evt.Sys[S]](op: Int, in: DataInput, access: S#Acc, targets: evt.Targets[S])
                                                    (implicit tx: S#Tx): Repr[S] with evt.Node[S] = {
    val ext = findExt(op)
    if (ext == null) sys.error(s"Unknown extension operator $op")
    ext.readExtension[S](op, in, access, targets)
  }

  def readValue(in: DataInput): ParamSpec = {
    val cookie = in.readInt()
    if (cookie != COOKIE) sys.error(s"Unexpected cookie (found $cookie, expected $COOKIE)")

    val lo    = in.readDouble()
    val hi    = in.readDouble()
    val warp  = Warp.read(in)
    // val step  = in.readDouble()
    val unit  = in.readUTF()
    ParamSpec(lo = lo, hi = hi, warp = warp, /* step = step, */ unit = unit)
  }

  def writeValue(value: ParamSpec, out: DataOutput): Unit = {
    out.writeInt(COOKIE)
    import value._
    out.writeDouble(lo)
    out.writeDouble(hi)
    warp.write(out)
    // out.writeDouble(step)
    out.writeUTF(unit)
  }

  def readConst[S <: Sys[S]](in: DataInput): Repr[S] with Expr.Const[S, ParamSpec] = {
    val cookie = in.readByte()
    if (cookie != 3) sys.error(s"Unexpected cookie $cookie")
    newConst[S](readValue(in))
  }

  def newConst[S <: Sys[S]](value: ParamSpec): Repr[S] with Expr.Const[S, ParamSpec] = Const[S](value)

  def read[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): Repr[S] = serializer[S].read(in, access)

  def apply[S <: Sys[S]](lo: Expr[S, Double], hi: Expr[S, Double], warp: Expr[S, Warp],
                         step: Expr[S, Double], unit: Expr[S, String])(implicit tx: S#Tx): Repr[S] =
    (lo, hi, warp, step, unit) match {
      case (Expr.Const(loC), Expr.Const(hiC), Expr.Const(warpC), Expr.Const(stepC), Expr.Const(unitC)) =>
        val v = ParamSpec(lo = loC, hi = hiC, warp = warpC, /* step = stepC, */ unit = unitC)
        newConst[S](v)
      case _ =>
        val targets = evt.Targets[S]
        new Apply[S](targets, loEx = lo, hiEx = hi, warpEx = warp, /* stepEx = step, */ unitEx = unit)
    }

  implicit def serializer[S <: Sys[S]]: evt.Serializer[S, Repr[S]] = anySer.asInstanceOf[Ser[S]]

  private[this] final val anySer = new Ser[InMemory]

  private[this] final class Ser[S <: Sys[S]] extends evt.EventLikeSerializer[S, Repr[S]] {
    def readConstant(in: DataInput)(implicit tx: S#Tx): Repr[S] = newConst[S](readValue(in))

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
    val ref = tx.readVar[Repr[S]](targets.id, in)
    new Var[S](targets, ref)
  }

  private[this] def readNode[S <: Sys[S]](cookie: Int, in: DataInput, access: S#Acc, targets: evt.Targets[S])
                                         (implicit tx: S#Tx): Repr[S] with evt.Node[S] = {
    val op = in.readInt()
    readExtension(op, in, access, targets)
  }

  private[this] final case class Const[S <: Sys[S]](constValue: ParamSpec)
    extends ParamSpec.Expr[S] /* Repr[S] */ with expr.impl.ConstImpl[S, ParamSpec] {

    def lo  (implicit tx: S#Tx): Expr[S, Double] = DoubleEx .newConst(constValue.lo  )
    def hi  (implicit tx: S#Tx): Expr[S, Double] = DoubleEx .newConst(constValue.hi  )
    def warp(implicit tx: S#Tx): Expr[S, Warp  ] = Warp.Expr.newConst(constValue.warp)
    // def step(implicit tx: S#Tx): Expr[S, Double] = DoubleEx .newConst(constValue.step)
    def unit(implicit tx: S#Tx): Expr[S, String] = StringEx .newConst(constValue.unit)

    protected def writeData(out: DataOutput): Unit = writeValue(constValue, out)
  }

  object Apply extends Type.Extension1[Repr] {
    def readExtension[S <: Sys[S]](opID: Int, in: DataInput, access: S#Acc, targets: evt.Targets[S])
                                  (implicit tx: S#Tx): Repr[S] with evt.Node[S] = {
      val lo    = DoubleEx .read(in, access)
      val hi    = DoubleEx .read(in, access)
      val warp  = Warp.Expr.read(in, access)
      // val step  = DoubleEx .read(in, access)
      val unit  = StringEx .read(in, access)
      new Apply(targets, loEx = lo, hiEx = hi, warpEx = warp, /* stepEx = step, */ unitEx = unit)
    }

    def name: String = "Apply"

    final val opLo = 0
    final val opHi = 0
  }
  private[this] final class Apply[S <: Sys[S]](protected val targets: evt.Targets[S],
                                               loEx: Expr[S, Double], hiEx: Expr[S, Double],
                                               warpEx: Expr[S, Warp], // stepEx: Expr[S, Double],
                                               unitEx: Expr[S, String])
    extends ParamSpec.Expr[S] // Repr[S]
    with evt.impl.StandaloneLike  [S, Change[ParamSpec], Repr[S]]
    with evt.impl.EventImpl       [S, Change[ParamSpec], Repr[S]]
    /* with evt.impl.MappingGenerator[S, Change[ParamSpec], Repr[S]] */ {

    protected def writeData(out: DataOutput): Unit = {
      out   .writeByte(1) // 'op'
      out   .writeInt(Apply.opLo)
      loEx  .write(out)
      hiEx  .write(out)
      warpEx.write(out)
      // stepEx.write(out)
      unitEx.write(out)
    }

    protected def disposeData()(implicit tx: S#Tx): Unit = ()

    def value(implicit tx: S#Tx): ParamSpec = {
      val loC   = loEx.value
      val hiC   = hiEx.value
      val warpC = warpEx.value
      // val stepC = stepEx.value
      val unitC = unitEx.value
      ParamSpec(lo = loC, hi = hiC, warp = warpC, /* step = stepC, */ unit = unitC)
    }

    def lo  (implicit tx: S#Tx): Expr[S, Double] = loEx
    def hi  (implicit tx: S#Tx): Expr[S, Double] = hiEx
    def warp(implicit tx: S#Tx): Expr[S, Warp  ] = warpEx
    // def step(implicit tx: S#Tx): Expr[S, Double] = stepEx
    def unit(implicit tx: S#Tx): Expr[S, String] = unitEx

    // ---- event ----

    def connect   ()(implicit tx: S#Tx): Unit = {
      loEx  .changed ---> this
      hiEx  .changed ---> this
      warpEx.changed ---> this
      // stepEx.changed ---> this
      unitEx.changed ---> this
    }

    def disconnect()(implicit tx: S#Tx): Unit = {
      loEx  .changed -/-> this
      hiEx  .changed -/-> this
      warpEx.changed -/-> this
      // stepEx.changed -/-> this
      unitEx.changed -/-> this
    }

    def pullUpdate(pull: evt.Pull[S])(implicit tx: S#Tx): Option[Change[ParamSpec]] = {
      val loEvt = loEx.changed
      val loCh  = (if (pull.contains(loEvt)) pull(loEvt) else None).getOrElse {
        val loV = loEx.value
        Change(loV, loV)
      }
      val hiEvt = hiEx.changed
      val hiCh  = (if (pull.contains(hiEvt)) pull(hiEvt) else None).getOrElse {
        val hiV = hiEx.value
        Change(hiV, hiV)
      }
      val warpEvt = warpEx.changed
      val warpCh  = (if (pull.contains(warpEvt)) pull(warpEvt) else None).getOrElse {
        val warpV = warpEx.value
        Change(warpV, warpV)
      }
      //      val stepEvt = stepEx.changed
      //      val stepCh = (if (pull.contains(stepEvt)) pull(stepEvt) else None).getOrElse {
      //        val stepV = stepEx.value
      //        Change(stepV, stepV)
      //      }
      val unitEvt = unitEx.changed
      val unitCh  = (if (pull.contains(unitEvt)) pull(unitEvt) else None).getOrElse {
        val unitV = unitEx.value
        Change(unitV, unitV)
      }
      val before = ParamSpec(lo = loCh.before, hi = hiCh.before, warp = warpCh.before, /* step = stepCh.before, */ unit = unitCh.before)
      val now    = ParamSpec(lo = loCh.now   , hi = hiCh.now   , warp = warpCh.now   , /* step = stepCh.now   , */ unit = unitCh.now   )
      Some(Change(before, now))
    }

    def reader: evt.Reader[S, Repr[S]] = ParamSpecExprImpl.serializer[S]

    def changed: EventLike[S, Change[ParamSpec]] = this
  }

  private[this] final class Var[S <: Sys[S]](val targets: evt.Targets[S], val ref: S#Var[Repr[S]])
    extends ParamSpec.Expr[S] // Repr[S]
    with evt.impl.StandaloneLike[S, Change[ParamSpec], Repr[S]]
    with evt.impl.Generator[S, Change[ParamSpec], Repr[S]] with evt.InvariantSelector[S]
    with stm.Var[S#Tx, Repr[S]] {

    def apply()(implicit tx: S#Tx): Repr[S] = ref()

    def update(expr: Repr[S])(implicit tx: S#Tx): Unit = {
      val before = ref()
      if (before != expr) {
        val con = targets.nonEmpty
        if (con) before.changed -/-> this
        ref() = expr
        if (con) {
          expr.changed ---> this
          val beforeV = before.value
          val exprV   = expr.value
          fire(Change(beforeV, exprV))
        }
      }
    }

    def transform(f: Repr[S] => Repr[S])(implicit tx: S#Tx): Unit = update(f(apply()))

    def value(implicit tx: S#Tx): ParamSpec = apply().value

    def lo  (implicit tx: S#Tx): Expr[S, Double] = mkTuple1[S, Double](this, DoubleExtensions.Lo)
    def hi  (implicit tx: S#Tx): Expr[S, Double] = mkTuple1[S, Double](this, DoubleExtensions.Hi)
    def warp(implicit tx: S#Tx): Expr[S, Warp  ] = mkTuple1[S, Warp  ](this, WarpExtensions.Warp)
    // def step(implicit tx: S#Tx): Expr[S, Double] = mkTuple1(this, DoubleExtensions.Step)
    def unit(implicit tx: S#Tx): Expr[S, String] = mkTuple1[S, String](this, StringExtensions.Unit)

    def changed: EventLike[S, Change[ParamSpec]] = this

    protected def writeData(out: DataOutput): Unit = {
      out.writeByte(0)  // 'var'
      ref.write(out)
    }

    protected def disposeData()(implicit tx: S#Tx): Unit = ref.dispose()

    def connect   ()(implicit tx: S#Tx): Unit = ref().changed ---> this
    def disconnect()(implicit tx: S#Tx): Unit = ref().changed -/-> this

    def reader: evt.Reader[S, Repr[S]] = serializer[S]

    def pullUpdate(pull: evt.Pull[S])(implicit tx: S#Tx): Option[Change[ParamSpec]] =
      if (pull.parents(this).isEmpty) {
        Some(pull.resolve[Change[ParamSpec]])
      } else {
        pull(ref().changed)
      }

    override def toString() = s"ParamSpec.Expr.Var$id"
  }

  object DoubleExtensions
    extends Type.Extension1[({type Repr[~ <: Sys[~]] = Expr[~, Double]})#Repr] {

    final val arity = 1
    final val opLo  = Lo  .id
    final val opHi  = Hi /* Step */.id

    val name = "ParamSpec-Double Ops"

    def readExtension[S <: Sys[S]](opID: Int, in: DataInput, access: S#Acc, targets: evt.Targets[S])
                                  (implicit tx: S#Tx): Expr.Node[S, Double] = {
      val op: Tuple1Op[Double] = (opID: @switch) match {
        case Lo  .id => Lo
        case Hi  .id => Hi
        // case Step.id => Step
        case _ => sys.error(s"Invalid operation id $opID")
      }
      val _1 = read(in, access)
      new Tuple1[S, Double](op, targets, _1)
    }

    sealed trait Op extends Tuple1Op[Double] {
      def exprType = DoubleEx
    }

    object Lo extends Op {
      final val id = 1000

      def value(a: ParamSpec) = a.lo
    }

    object Hi extends Op {
      final val id = 1001

      def value(a: ParamSpec) = a.hi
    }

    //    object Step extends Op {
    //      final val id = 1002
    //
    //      def value(a: ParamSpec) = a.step
    //    }
  }

  object StringExtensions
    extends Type.Extension1[({type Repr[~ <: Sys[~]] = Expr[~, String]})#Repr] {

    final val arity = 1
    final val opLo  = Unit.id
    final val opHi  = Unit.id

    val name = "ParamSpec-String Ops"

    def readExtension[S <: Sys[S]](opID: Int, in: DataInput, access: S#Acc, targets: evt.Targets[S])
                                  (implicit tx: S#Tx): Expr.Node[S, String] = {
      val op: Tuple1Op[String] = opID /* : @switch */ match {
        case Unit.id => Unit
        case _ => sys.error(s"Invalid operation id $opID")
      }
      val _1 = read(in, access)
      new Tuple1[S, String](op, targets, _1)
    }

    sealed trait Op extends Tuple1Op[String] {
      def exprType = StringEx
    }

    object Unit extends Op {
      final val id = 1000

      def value(a: ParamSpec) = a.unit
    }
  }

  object WarpExtensions
    extends Type.Extension1[({type Repr[~ <: Sys[~]] = Expr[~, nuages.Warp]})#Repr] {

    final val arity = 1
    final val opLo  = Warp.id
    final val opHi  = Warp.id

    val name = "ParamSpec-Warp Ops"

    def readExtension[S <: Sys[S]](opID: Int, in: DataInput, access: S#Acc, targets: evt.Targets[S])
                                  (implicit tx: S#Tx): Expr.Node[S, nuages.Warp] = {
      val op: Tuple1Op[nuages.Warp] = opID /* : @switch */ match {
        case Warp.id => Warp
        case _ => sys.error(s"Invalid operation id $opID")
      }
      val _1 = read(in, access)
      new Tuple1[S, nuages.Warp](op, targets, _1)
    }

    sealed trait Op extends Tuple1Op[nuages.Warp] {
      def exprType = nuages.Warp.Expr
    }

    object Warp extends Op {
      final val id = 1000

      def value(a: ParamSpec) = a.warp
    }
  }

  private final class Tuple1[S <: Sys[S], A](val op: Tuple1Op[A], val targets: evt.Targets[S], val _1: Repr[S])
    extends Expr.Node[S, A]
    with evt.impl.StandaloneLike[S, Change[A], Expr[S, A]]
    with evt.InvariantSelector[S] {

    def value(implicit tx: S#Tx): A = op.value(_1.value)

    override def toString() = s"${_1}.${op.toString.toLowerCase}"

    protected def writeData(out: DataOutput): Unit = {
      out.writeByte(1)
      out.writeInt(typeID)
      out.writeInt(op.id)
      _1.write(out)
    }

    protected def disposeData()(implicit tx: S#Tx) = ()

    // ---- event ----

    protected def reader = op.exprType.serializer[S]

    def connect   ()(implicit tx: S#Tx): Unit = _1.changed ---> this
    def disconnect()(implicit tx: S#Tx): Unit = _1.changed -/-> this

    def pullUpdate(pull: evt.Pull[S])(implicit tx: S#Tx): Option[Change[A]] =
      pull(_1.changed).flatMap { ach =>
        val before  = op.value(ach.before)
        val now     = op.value(ach.now   )
        if (before == now) None else Some(Change(before, now))
      }

    def changed: Event[S, Change[A], Expr[S, A]] = this
  }

  abstract class Tuple1Op[A] {
    def id: Int

    def exprType: ExprType1[A]

    // def expr[S <: Sys[S]](a: Repr[S]): Ex[S, A]

    def value(a: ParamSpec): A

    final def unapply[S <: Sys[S]](ex: Expr[S, A])(implicit tx: S#Tx): Option[Repr[S]] = ex match {
      case tup: Tuple1[S, A] if tup.op == this => Some(tup._1) // Some(tup._1.asInstanceOf[Expr[S, T1]])
      case _ => None
    }
  }

  private[this] def mkTuple1[S <: Sys[S], A](repr: Repr[S], op: Tuple1Op[A])(implicit tx: S#Tx): Tuple1[S, A] = {
    val targets = evt.Targets[S]
    new Tuple1(op, targets, repr)
  }
}

object ParamSpecElemImpl extends proc.impl.ElemCompanionImpl[ParamSpec.Elem] {
  def typeID = ParamSpec.typeID

  private lazy val _init: Unit = Elem.registerExtension(this)
  def init(): Unit = _init

  def apply[S <: Sys[S]](peer: ParamSpec.Expr[S])(implicit tx: S#Tx): ParamSpec.Elem[S] = {
    val targets = evt.Targets[S]
    new Impl[S](targets, peer)
  }

  def read[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): ParamSpec.Elem[S] =
    serializer[S].read(in, access)

  // ---- Elem.Extension ----

  /** Read identified active element */
  def readIdentified[S <: Sys[S]](in: DataInput, access: S#Acc, targets: evt.Targets[S])
                                 (implicit tx: S#Tx): ParamSpec.Elem[S] with evt.Node[S] = {
    val peer = ParamSpec.Expr.read(in, access)
    new Impl[S](targets, peer)
  }

  /** Read identified constant element */
  def readIdentifiedConstant[S <: Sys[S]](in: DataInput)(implicit tx: S#Tx): ParamSpec.Elem[S] =
    sys.error("Constant ParamSpec not supported")

  // ---- implementation ----

  private final class Impl[S <: Sys[S]](protected val targets: evt.Targets[S],
                                        val peer: ParamSpec.Expr[S])
    extends ParamSpec.Elem[S]
    with proc.impl.ActiveElemImpl[S] {

    def typeID = ParamSpec.typeID
    def prefix = "ParamSpec"

    override def toString() = s"$prefix$id"

    def mkCopy()(implicit tx: S#Tx): ParamSpec.Elem[S] = ParamSpec.Elem(peer)
  }
}