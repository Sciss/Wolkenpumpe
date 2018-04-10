/*
 *  NuagesBooleanAttrInput.scala
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

import de.sciss.lucre.expr.{BooleanObj, Type}
import de.sciss.lucre.stm.Sys
import de.sciss.lucre.synth.{Sys => SSys}

object NuagesBooleanAttrInput extends PassAttrInputFactory {
  def typeId: Int = BooleanObj.typeId

  type Repr[~ <: Sys[~]] = BooleanObj[~]

  protected def mkNoInit[S <: SSys[S]](attr: NuagesAttribute[S])
                                     (implicit tx: S#Tx, context: NuagesContext[S]): View[S] = {
    new NuagesBooleanAttrInput[S](attr)
  }
}
final class NuagesBooleanAttrInput[S <: SSys[S]] private (val attribute: NuagesAttribute[S])
  extends NuagesScalarAttrInput[S] {

  type A                  = Boolean
  type Repr[~ <: Sys[~]]  = BooleanObj[~]

  val tpe: Type.Expr[A, Repr] = BooleanObj

  protected def toDouble  (in: Boolean): Double   = if (in) 1.0 else 0.0
  protected def fromDouble(in: Double ): Boolean  = in == 0.0
}