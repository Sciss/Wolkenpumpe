/*
 *  NuagesDoubleAttrInput.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2015 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
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

object NuagesDoubleAttrInput extends NuagesAttribute.Factory {
  def typeID: Int = DoubleObj.typeID

  type Repr[~ <: Sys[~]] = DoubleObj[~]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: NuagesAttribute.Parent[S], obj: DoubleObj[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute.Input[S] = {
//    val spec  = NuagesAttributeImpl.getSpec(parent, key)
    new NuagesDoubleAttrInput[S](attr, parent = parent).init(obj)
  }
}
final class NuagesDoubleAttrInput[S <: SSys[S]] private (val attribute: NuagesAttribute[S],
                                                         protected val parent: NuagesAttribute.Parent[S])
  extends NuagesScalarAttrInput[S] {

  type A                = Double
  type Ex[~ <: Sys[~]]  = DoubleObj[~]

  def tpe: Type.Expr[A, Ex] = DoubleObj

  protected def toDouble  (in: Double): Double = in
  protected def fromDouble(in: Double): Double = in
}