/*
 *  NuagesBooleanAttrInput.scala
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

import de.sciss.lucre.expr.{BooleanObj, Type}
import de.sciss.lucre.stm.Sys
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.{Input, Parent}

import scala.concurrent.stm.Ref

object NuagesBooleanAttrInput extends NuagesAttributeSingleFactory {
  def typeID: Int = BooleanObj.typeID

  type Repr[~ <: Sys[~]] = BooleanObj[~]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: Parent[S], frameOffset: Long, obj: BooleanObj[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] = {
    // val spec  = NuagesAttributeImpl.getSpec(attr.parent, key)
    new NuagesBooleanAttrInput[S](attr).init(obj, parent)
  }
}
final class NuagesBooleanAttrInput[S <: SSys[S]] private (val attribute: NuagesAttribute[S])
  extends NuagesScalarAttrInput[S] {

  type A                = Boolean
  type Ex[~ <: Sys[~]]  = BooleanObj[~]

  def tpe: Type.Expr[A, Ex] = BooleanObj

  protected def toDouble  (in: Boolean): Double   = if (in) 1.0 else 0.0
  protected def fromDouble(in: Double ): Boolean  = in == 0.0
}