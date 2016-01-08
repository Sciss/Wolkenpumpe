package de.sciss.nuages
package impl

import de.sciss.lucre.stm.{Obj, Sys}
import de.sciss.nuages.NuagesAttribute.Input

import scala.collection.immutable.{IndexedSeq => Vec}

class DummyAttrInput[S <: Sys[S]](val attribute: NuagesAttribute[S])
  extends NuagesAttrInputBase[S] {

  def numChannels: Int = 1

  def value: Vec[Double] = Vector(0.0)

  def tryConsume(newValue: Obj[S])(implicit tx: S#Tx): Boolean = false

  def dispose()(implicit tx: S#Tx): Unit = ()

  def collect[A](pf: PartialFunction[Input[S], A])(implicit tx: S#Tx): Iterator[A] = Iterator.empty
}
