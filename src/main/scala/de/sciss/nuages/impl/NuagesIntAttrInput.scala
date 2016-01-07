/*
 *  NuagesIntAttrInput.scala
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

import de.sciss.lucre.expr.{IntObj, Type}
import de.sciss.lucre.stm.Sys
import de.sciss.lucre.synth.{Sys => SSys}

object NuagesIntAttrInput extends NuagesAttributeSingleFactory {
  def typeID: Int = IntObj.typeID

  type Repr[~ <: Sys[~]] = IntObj[~]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: NuagesAttribute.Parent[S], obj: IntObj[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute.Input[S] = {
//    val spec  = NuagesAttributeImpl.getSpec(parent, key)
    new NuagesIntAttrInput[S](attr, inputParent = parent).init(obj)
  }
}
final class NuagesIntAttrInput[S <: SSys[S]](val attribute: NuagesAttribute[S],
                                             val inputParent: NuagesAttribute.Parent[S])
  extends NuagesScalarAttrInput[S] {

  type A                = Int
  type Ex[~ <: Sys[~]]  = IntObj[~]

  def tpe: Type.Expr[A, Ex] = IntObj

  protected def toDouble  (in: Int   ): Double = in.toDouble
  protected def fromDouble(in: Double): Int    = in.toInt
}