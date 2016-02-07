/*
 *  NuagesDoubleVectorAttrInput.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2016 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.lucre.expr.Expr.Const
import de.sciss.lucre.expr.{DoubleVector, Type}
import de.sciss.lucre.stm.Sys
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.{Input, Parent}

import scala.collection.immutable.{IndexedSeq => Vec}

object NuagesDoubleVectorAttrInput extends NuagesAttributeSingleFactory {
  def typeID: Int = DoubleVector.typeID

  type Repr[~ <: Sys[~]] = DoubleVector[~]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: Parent[S], frameOffset: Long, obj: DoubleVector[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] =
    new NuagesDoubleVectorAttrInput[S](attr).init(obj, parent)
}
final class NuagesDoubleVectorAttrInput[S <: SSys[S]](val attribute: NuagesAttribute[S])
  extends RenderAttrDoubleVec[S] with NuagesAttrInputImpl[S] {

  type Ex[~ <: Sys[~]]  = DoubleVector[~]

  def tpe: Type.Expr[A, Ex] = DoubleVector

  protected def mkConst(v: Vec[Double])(implicit tx: S#Tx): DoubleVector[S] with Const[S, Vec[Double]] =
    tpe.newConst(v)

  def value: Vec[Double] = valueA

  def numChannels = valueA.size

  def numChildren(implicit tx: S#Tx): Int = 1
}