/*
 *  NuagesDoubleAttrInput.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2019 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.lucre.expr.{DoubleObj, Type}
import de.sciss.lucre.stm.Sys
import de.sciss.lucre.synth.{Sys => SSys}

object NuagesDoubleAttrInput extends PassAttrInputFactory {
  def typeId: Int = DoubleObj.typeId

  type Repr[~ <: Sys[~]] = DoubleObj[~]

  protected def mkNoInit[S <: SSys[S]](attr: NuagesAttribute[S])
                                     (implicit tx: S#Tx, context: NuagesContext[S]): View[S] =
    new NuagesDoubleAttrInput[S](attr)
}
final class NuagesDoubleAttrInput[S <: SSys[S]] private (val attribute: NuagesAttribute[S])
  extends NuagesScalarAttrInput[S] {

  override def toString = s"Double($attribute)"

  type A                  = Double
  type Repr[~ <: Sys[~]]  = DoubleObj[~]

  val tpe: Type.Expr[A, Repr] = DoubleObj

  protected def toDouble  (in: Double): Double = in
  protected def fromDouble(in: Double): Double = in
}