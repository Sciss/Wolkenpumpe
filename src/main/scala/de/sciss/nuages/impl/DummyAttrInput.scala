/*
 *  DummyAttrInput.scala
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

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Obj, Sys}
import de.sciss.nuages.NuagesAttribute.Input

import scala.collection.immutable.{IndexedSeq => Vec}

class DummyAttrInput[S <: Sys[S]](val attribute: NuagesAttribute[S], objH: stm.Source[S#Tx, Obj[S]])
  extends NuagesAttrInputBase[S] {

  def numChannels: Int = 1

  def numChildren(implicit tx: S#Tx): Int = 1

  def value: Vec[Double] = Vector(0.0)

  def tryConsume(newOffset: Long, newValue: Obj[S])(implicit tx: S#Tx): Boolean = false

  def dispose()(implicit tx: S#Tx): Unit = ()

  def collect[A](pf: PartialFunction[Input[S], A])(implicit tx: S#Tx): Iterator[A] = Iterator.empty

  def input(implicit tx: S#Tx): Obj[S] = objH()
}
