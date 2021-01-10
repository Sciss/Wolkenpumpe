/*
 *  NuagesDoubleVectorAttrInput.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2021 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.lucre.{DoubleVector, Expr, Txn, synth}
import de.sciss.synth.Curve
import de.sciss.proc.EnvSegment

import scala.collection.immutable.{IndexedSeq => Vec}

object NuagesDoubleVectorAttrInput extends PassAttrInputFactory {
  def typeId: Int = DoubleVector.typeId

  type Repr[~ <: Txn[~]] = DoubleVector[~]

  protected def mkNoInit[T <: synth.Txn[T]](attr: NuagesAttribute[T])
                                     (implicit tx: T, context: NuagesContext[T]): View[T] =
    new NuagesDoubleVectorAttrInput[T](attr)
}
final class NuagesDoubleVectorAttrInput[T <: synth.Txn[T]](val attribute: NuagesAttribute[T])
  extends RenderAttrDoubleVec[T] with NuagesAttrInputExprImpl[T] {

  override def toString = s"DoubleVector($attribute)"

  type Repr[~ <: Txn[~]]  = DoubleVector[~]

  val tpe: Expr.Type[A, Repr] = DoubleVector

  protected def mkConst(v: Vec[Double])(implicit tx: T): DoubleVector[T] =
    tpe.newConst(v)

  protected def mkEnvSeg(start: Repr[T], curve: Curve)(implicit tx: T): EnvSegment.Obj[T] = {
    val lvl = start.value
    EnvSegment.Obj.newVar[T](EnvSegment.Multi(lvl, curve))
  }

  def numChannels: Int = valueA.size
}