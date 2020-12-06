/*
 *  NuagesEnvSegmentAttrInput.scala
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
package impl

import de.sciss.lucre.{DoubleObj, DoubleVector, Expr, Txn, synth}
import de.sciss.lucre.Txn.peer
import de.sciss.synth.Curve
import de.sciss.proc.EnvSegment

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.swing.Color

object NuagesEnvSegmentAttrInput extends PassAttrInputFactory {
  def typeId: Int = EnvSegment.typeId

  type Repr[~ <: Txn[~]] = EnvSegment.Obj[~]

  protected def mkNoInit[T <: synth.Txn[T]](attr: NuagesAttribute[T])
                                     (implicit tx: T, context: NuagesContext[T]): View[T] =
    new NuagesEnvSegmentAttrInput[T](attr)
}
final class NuagesEnvSegmentAttrInput[T <: synth.Txn[T]](val attribute: NuagesAttribute[T])
  extends RenderNumericAttr[T] with NuagesAttrInputImpl[T] {

  override def toString = s"EnvSegment($attribute)"

  type A                  = Vec[Double]
  type B                  = EnvSegment
  type Repr [~ <: Txn[~]] = EnvSegment.Obj[~]

  val tpe: Expr.Type[B, Repr] = EnvSegment.Obj

  protected def valueColor: Color = NuagesDataImpl.colrMapped

  protected def updateValueAndRefresh(v: EnvSegment)(implicit tx: T): Unit = {
    main.deferVisTx {
      _numChannels = v.numChannels
      damageReport(pNode)
    }
  }

  protected def valueA: Vec[Double] = attribute.numericValue

  @volatile
  private[this] var _numChannels: Int = _

  override def passFrom(that: PassAttrInput[T])(implicit tx: T): Unit = {
    main.deferVisTx {
      dragOption = that.dragOption
    }
    super.passFrom(that)
  }

  /** On the EDT! */
  def numericValue: Vec[Double] = {
//    val v         = valueA
//////    val t         = System.currentTimeMillis()
//////    val dtMillis  = 10000.0 // XXX TODO
//////    val pos       = math.min(1.0f, ((t - valueSetTime) / dtMillis).toFloat)
////    // v.curve.levelAt(pos = pos, y1 = ..., y2 = ...)
//    val lvl = v.startLevels
//////    lvl.map(x => math.min(1.0, x + math.random() * 0.1))
//    lvl
//
    attribute.numericValue
  }

//  override protected def updateValueAndRefresh(v: A)(implicit tx: T): Unit =
//    main.deferVisTx {
//      valueA        = v
//      valueSetTime  = System.currentTimeMillis()
//      damageReport(pNode)
//    }

  def numChannels: Int = _numChannels

  override def init(obj: EnvSegment.Obj[T], parent: NuagesAttribute.Parent[T])(implicit tx: T): Unit = {
    _numChannels = obj.value.numChannels
    super.init(obj, parent)
  }

  protected def setControlTxn(v: Vec[Double], durFrames: Long)(implicit tx: T): Unit = {
    val before = objH()._1()

    val nowVar = if (v.size == 1) DoubleObj.newVar[T](v.head) else DoubleVector.newVar[T](v)
    if (durFrames == 0L)
      inputParent.updateChild(before = before, now = nowVar, dt = 0L, clearRight = true)
    else {
      val seg = if (v.size == 1) {
        EnvSegment.Obj.newVar[T](EnvSegment.Single(v.head, Curve.lin))
      } else {
        EnvSegment.Obj.newVar[T](EnvSegment.Multi(v, Curve.lin))
      }
      inputParent.updateChild(before = before, now = nowVar, dt = durFrames, clearRight = true )
      inputParent.updateChild(before = before, now = seg   , dt = 0L       , clearRight = false)
    }
  }
}