/*
 *  NuagesBooleanAttrInput.scala
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

import de.sciss.lucre.Txn
import de.sciss.lucre.{BooleanObj, Expr, synth}

object NuagesBooleanAttrInput extends PassAttrInputFactory {
  def typeId: Int = BooleanObj.typeId

  type Repr[~ <: Txn[~]] = BooleanObj[~]

  protected def mkNoInit[T <: synth.Txn[T]](attr: NuagesAttribute[T])
                                     (implicit tx: T, context: NuagesContext[T]): View[T] = {
    new NuagesBooleanAttrInput[T](attr)
  }
}
final class NuagesBooleanAttrInput[T <: synth.Txn[T]] private (val attribute: NuagesAttribute[T])
  extends NuagesScalarAttrInput[T] {

  type A                  = Boolean
  type Repr[~ <: Txn[~]]  = BooleanObj[~]

  val tpe: Expr.Type[A, Repr] = BooleanObj

  protected def toDouble  (in: Boolean): Double   = if (in) 1.0 else 0.0
  protected def fromDouble(in: Double ): Boolean  = in == 0.0
}