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

import java.awt.Graphics2D

import de.sciss.lucre.expr.Expr.Const
import de.sciss.lucre.expr.{Expr, Type}
import de.sciss.lucre.stm.Sys
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.{Input, Parent}
import de.sciss.synth.Curve
import de.sciss.synth.proc.EnvSegment
import prefuse.visual.VisualItem

import scala.collection.immutable.{IndexedSeq => Vec}

object NuagesEnvSegmentAttrInput extends NuagesAttributeSingleFactory {
  def typeID: Int = EnvSegment.typeID

  type Repr[~ <: Sys[~]] = EnvSegment.Obj[~]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: Parent[S], frameOffset: Long, obj: Repr[S])
                         (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] =
    new NuagesEnvSegmentAttrInput[S](attr).init(obj, parent)
}
final class NuagesEnvSegmentAttrInput[S <: SSys[S]](val attribute: NuagesAttribute[S])
  extends RenderNumericAttr[S] with NuagesAttrInputImpl[S] {

  type A                = Vec[Double]
  type B                = EnvSegment
  type Ex[~ <: Sys[~]]  = EnvSegment.Obj[~]

  def tpe: Type.Expr[B, Ex] = EnvSegment.Obj

//  private[this] var valueSetTime = System.currentTimeMillis()

  protected def updateValueAndRefresh(v: EnvSegment)(implicit tx: S#Tx): Unit = ???

  protected def valueA: Vec[Double] = attribute.numericValue

  protected def mkConst(v: Vec[Double])(implicit tx: S#Tx): Ex[S] with Const[S, B] =
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

  protected def mkEnvSeg(start: Ex[S], curve: Curve)(implicit tx: S#Tx): EnvSegment.Obj[S] = {
    ???!
//    val lvl = start.value
//    EnvSegment.Obj.newVar[S](EnvSegment.Multi(lvl, curve))
  }

  def numChannels: Int = 1 // XXX TODO valueA.numChannels
}