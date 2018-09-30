/*
 *  NuagesEnvSegmentAttrInput.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2018 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.lucre.expr.{DoubleObj, DoubleVector, Type}
import de.sciss.lucre.stm.Sys
import de.sciss.lucre.stm.TxnLike.peer
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.synth.Curve
import de.sciss.synth.proc.EnvSegment

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.swing.Color

object NuagesEnvSegmentAttrInput extends PassAttrInputFactory {
  def typeId: Int = EnvSegment.typeId

  type Repr[~ <: Sys[~]] = EnvSegment.Obj[~]

  protected def mkNoInit[S <: SSys[S]](attr: NuagesAttribute[S])
                                     (implicit tx: S#Tx, context: NuagesContext[S]): View[S] =
    new NuagesEnvSegmentAttrInput[S](attr)
}
final class NuagesEnvSegmentAttrInput[S <: SSys[S]](val attribute: NuagesAttribute[S])
  extends RenderNumericAttr[S] with NuagesAttrInputImpl[S] {

  override def toString = s"EnvSegment($attribute)"

  type A                  = Vec[Double]
  type B                  = EnvSegment
  type Repr [~ <: Sys[~]] = EnvSegment.Obj[~]

  val tpe: Type.Expr[B, Repr] = EnvSegment.Obj

  protected def valueColor: Color = NuagesDataImpl.colrMapped

  protected def updateValueAndRefresh(v: EnvSegment)(implicit tx: S#Tx): Unit = {
    main.deferVisTx {
      _numChannels = v.numChannels
      damageReport(pNode)
    }
  }

  protected def valueA: Vec[Double] = attribute.numericValue

  @volatile
  private[this] var _numChannels: Int = _

  override def passFrom(that: PassAttrInput[S])(implicit tx: S#Tx): Unit = {
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

//  override protected def updateValueAndRefresh(v: A)(implicit tx: S#Tx): Unit =
//    main.deferVisTx {
//      valueA        = v
//      valueSetTime  = System.currentTimeMillis()
//      damageReport(pNode)
//    }

  def numChannels: Int = _numChannels

  override def init(obj: EnvSegment.Obj[S], parent: NuagesAttribute.Parent[S])(implicit tx: S#Tx): Unit = {
    _numChannels = obj.value.numChannels
    super.init(obj, parent)
  }

  protected def setControlTxn(v: Vec[Double], durFrames: Long)(implicit tx: S#Tx): Unit = {
    val before = objH()._1()

    val nowVar = if (v.size == 1) DoubleObj.newVar(v.head) else DoubleVector.newVar(v)
    if (durFrames == 0L)
      inputParent.updateChild(before = before, now = nowVar, dt = 0L, clearRight = true)
    else {
      val seg = if (v.size == 1) {
        EnvSegment.Obj.newVar[S](EnvSegment.Single(v.head, Curve.lin))
      } else {
        EnvSegment.Obj.newVar[S](EnvSegment.Multi(v, Curve.lin))
      }
      inputParent.updateChild(before = before, now = nowVar, dt = durFrames, clearRight = true )
      inputParent.updateChild(before = before, now = seg   , dt = 0L       , clearRight = false)
    }
  }
}