/*
 *  NuagesAttrInputBase.scala
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
import de.sciss.nuages.NuagesAttribute.{Input, Parent}

import scala.concurrent.stm.Ref

trait NuagesAttrInputBase[T <: Txn[T]] extends NuagesAttribute.Input[T] {
  import Txn.peer

  private[this] val parentRef = Ref.make[Parent[T]]()

  final def inputParent                     (implicit tx: T): Parent[T]  = parentRef()
  final def inputParent_=(parent: Parent[T])(implicit tx: T): Unit       = parentRef() = parent

  protected final def isTimeline: Boolean = attribute.parent.main.isTimeline
}

/** I.e. not nested */
trait NuagesAttrSingleInput[T <: Txn[T]] extends NuagesAttrInputBase[T] {
  final def collect[B](pf: PartialFunction[Input[T], B])(implicit tx: T): Iterator[B] =
    if (pf.isDefinedAt(this)) Iterator.single(pf(this)) else Iterator.empty

  final def numChildren(implicit tx: T): Int = 1
}