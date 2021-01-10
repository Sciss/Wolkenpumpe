/*
 *  NuagesDoubleAttrInput.scala
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

import de.sciss.lucre.{DoubleObj, Expr, Txn, synth}

object NuagesDoubleAttrInput extends PassAttrInputFactory {
  def typeId: Int = DoubleObj.typeId

  type Repr[~ <: Txn[~]] = DoubleObj[~]

  protected def mkNoInit[T <: synth.Txn[T]](attr: NuagesAttribute[T])
                                     (implicit tx: T, context: NuagesContext[T]): View[T] =
    new NuagesDoubleAttrInput[T](attr)
}
final class NuagesDoubleAttrInput[T <: synth.Txn[T]] private (val attribute: NuagesAttribute[T])
  extends NuagesScalarAttrInput[T] {

  override def toString = s"Double($attribute)"

  type A                  = Double
  type Repr[~ <: Txn[~]]  = DoubleObj[~]

  val tpe: Expr.Type[A, Repr] = DoubleObj

  protected def toDouble  (in: Double): Double = in
  protected def fromDouble(in: Double): Double = in
}