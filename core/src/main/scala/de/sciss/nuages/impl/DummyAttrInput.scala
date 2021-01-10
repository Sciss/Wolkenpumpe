/*
 *  DummyAttrInput.scala
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

import de.sciss.lucre.{Obj, Source, Txn}
import de.sciss.nuages.NuagesAttribute.Input

import scala.collection.immutable.{IndexedSeq => Vec}

class DummyAttrInput[T <: Txn[T]](val attribute: NuagesAttribute[T], objH: Source[T, Obj[T]])
  extends NuagesAttrInputBase[T] {

  def numChannels: Int = 1

  def numChildren(implicit tx: T): Int = 1

  def value: Vec[Double] = Vector(0.0)

  def tryConsume(newOffset: Long, newValue: Obj[T])(implicit tx: T): Boolean = false

  def dispose()(implicit tx: T): Unit = ()

  def collect[A](pf: PartialFunction[Input[T], A])(implicit tx: T): Iterator[A] = Iterator.empty

  def input(implicit tx: T): Obj[T] = objH()
}
