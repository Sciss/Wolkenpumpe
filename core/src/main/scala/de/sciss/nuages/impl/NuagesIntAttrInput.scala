/*
 *  NuagesIntAttrInput.scala
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

import de.sciss.lucre.expr.{IntObj, Type}
import de.sciss.lucre.stm.Sys
import de.sciss.lucre.synth.{Sys => SSys}

object NuagesIntAttrInput extends PassAttrInputFactory {
  def typeId: Int = IntObj.typeId

  type Repr[~ <: Sys[~]] = IntObj[~]

  protected def mkNoInit[S <: SSys[S]](attr: NuagesAttribute[S])
                                     (implicit tx: S#Tx, context: NuagesContext[S]): View[S] =
    new NuagesIntAttrInput[S](attr)
}
final class NuagesIntAttrInput[S <: SSys[S]](val attribute: NuagesAttribute[S])
  extends NuagesScalarAttrInput[S] {

  override def toString = s"Int($attribute)"

  type A                  = Int
  type Repr[~ <: Sys[~]]  = IntObj[~]

  val tpe: Type.Expr[A, Repr] = IntObj

  protected def toDouble  (in: Int   ): Double = in.toDouble
  protected def fromDouble(in: Double): Int    = in.toInt
}