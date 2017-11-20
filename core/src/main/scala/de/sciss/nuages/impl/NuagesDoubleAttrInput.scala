/*
 *  NuagesDoubleAttrInput.scala
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

import de.sciss.lucre.expr.{DoubleObj, Type}
import de.sciss.lucre.stm.Sys
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.{Input, Parent}

object NuagesDoubleAttrInput extends NuagesAttributeSingleFactory {
  def typeID: Int = DoubleObj.typeID

  type Repr[~ <: Sys[~]] = DoubleObj[~]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: Parent[S], frameOffset: Long, obj: DoubleObj[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] = {
//    val spec  = NuagesAttributeImpl.getSpec(parent, key)
    new NuagesDoubleAttrInput[S](attr).init(obj, parent)
  }
}
final class NuagesDoubleAttrInput[S <: SSys[S]] private (val attribute: NuagesAttribute[S])
  extends NuagesScalarAttrInput[S] {

  type A                  = Double
  type Repr[~ <: Sys[~]]  = DoubleObj[~]

  val tpe: Type.Expr[A, Repr] = DoubleObj

  protected def toDouble  (in: Double): Double = in
  protected def fromDouble(in: Double): Double = in
}