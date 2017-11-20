/*
 *  NuagesEnvSegmentAttrInput.scala
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
package impl

import de.sciss.lucre.expr.Type
import de.sciss.lucre.stm.Sys
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.synth.Curve
import de.sciss.synth.proc.EnvSegment

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.swing.Color

object NuagesEnvSegmentAttrInput extends PassAttrInputFactory {
  def typeID: Int = EnvSegment.typeID

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

  protected def updateValueAndRefresh(v: EnvSegment)(implicit tx: S#Tx): Unit = ???

  protected def valueA: Vec[Double] = attribute.numericValue

  private def mkConst(v: Vec[Double])(implicit tx: S#Tx): Repr[S] =
    ???! // tpe.newConst(v)

  /** On the EDT! */
  def numericValue: Vec[Double] = {
//    val v         = valueA
//////    val t         = System.currentTimeMillis()
//////    val dtMillis  = 10000.0 // XXX TODO
//////    val pos       = math.min(1.0f, ((t - valueSetTime) / dtMillis).toFloat)
////    // v.curve.levelAt(pos = pos, y1 = ???, y2 = ???)
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

  private def mkEnvSeg(start: Repr[S], curve: Curve)(implicit tx: S#Tx): EnvSegment.Obj[S] = {
    ???!
//    val lvl = start.value
//    EnvSegment.Obj.newVar[S](EnvSegment.Multi(lvl, curve))
  }

  def numChannels: Int = 1 // XXX TODO valueA.numChannels

  protected def setControlTxn(v: Vec[Double], durFrames: Long)(implicit tx: S#Tx): Unit = {
    val nowConst: Repr[S]  = mkConst(v)
    val before  : Repr[S]  = ???! // objH()._1()

    val nowVar = tpe.newVar[S](nowConst)
    if (durFrames == 0L)
      inputParent.updateChild(before = before, now = nowVar, dt = 0L, clearRight = true)
    else {
      val seg = mkEnvSeg(before, Curve.lin) // EnvSegment.Obj.ApplySingle()
      inputParent.updateChild(before = before, now = nowVar, dt = durFrames, clearRight = true )
      inputParent.updateChild(before = before, now = seg   , dt = 0L       , clearRight = false)
    }
  }
}