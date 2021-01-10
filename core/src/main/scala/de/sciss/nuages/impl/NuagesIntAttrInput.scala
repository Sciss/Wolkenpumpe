/*
 *  NuagesIntAttrInput.scala
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

import de.sciss.lucre.{Expr, IntObj, Txn, synth}

object NuagesIntAttrInput extends PassAttrInputFactory {
  def typeId: Int = IntObj.typeId

  type Repr[~ <: Txn[~]] = IntObj[~]

  protected def mkNoInit[T <: synth.Txn[T]](attr: NuagesAttribute[T])
                                     (implicit tx: T, context: NuagesContext[T]): View[T] =
    new NuagesIntAttrInput[T](attr)
}
final class NuagesIntAttrInput[T <: synth.Txn[T]](val attribute: NuagesAttribute[T])
  extends NuagesScalarAttrInput[T] {

  override def toString = s"Int($attribute)"

  type A                  = Int
  type Repr[~ <: Txn[~]]  = IntObj[~]

  val tpe: Expr.Type[A, Repr] = IntObj

  protected def toDouble  (in: Int   ): Double = in.toDouble
  protected def fromDouble(in: Double): Int    = in.toInt
}