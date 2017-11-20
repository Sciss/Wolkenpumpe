/*
 *  NuagesDoubleVectorAttrInput.scala
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

import de.sciss.lucre.expr.{DoubleVector, Type}
import de.sciss.lucre.stm.Sys
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.synth.Curve
import de.sciss.synth.proc.EnvSegment

import scala.collection.immutable.{IndexedSeq => Vec}

object NuagesDoubleVectorAttrInput extends PassAttrInputFactory {
  def typeID: Int = DoubleVector.typeID

  type Repr[~ <: Sys[~]] = DoubleVector[~]

  protected def mkNoInit[S <: SSys[S]](attr: NuagesAttribute[S])
                                     (implicit tx: S#Tx, context: NuagesContext[S]): View[S] =
    new NuagesDoubleVectorAttrInput[S](attr)
}
final class NuagesDoubleVectorAttrInput[S <: SSys[S]](val attribute: NuagesAttribute[S])
  extends RenderAttrDoubleVec[S] with NuagesAttrInputExprImpl[S] {

  override def toString = s"DoubleVector($attribute)"

  type Repr[~ <: Sys[~]]  = DoubleVector[~]

  val tpe: Type.Expr[A, Repr] = DoubleVector

  protected def mkConst(v: Vec[Double])(implicit tx: S#Tx): DoubleVector[S] =
    tpe.newConst(v)

  protected def mkEnvSeg(start: Repr[S], curve: Curve)(implicit tx: S#Tx): EnvSegment.Obj[S] = {
    val lvl = start.value
    EnvSegment.Obj.newVar[S](EnvSegment.Multi(lvl, curve))
  }

  def numChannels: Int = valueA.size
}