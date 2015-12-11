package de.sciss.nuages
package impl

import de.sciss.lucre.stm.Sys
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.synth.proc.Output

import scala.collection.immutable.{IndexedSeq => Vec}

object NuagesOutputAttrInput extends NuagesAttribute.Factory {
  def typeID: Int = Output.typeID

  type Repr[~ <: Sys[~]] = Output[~]

  def apply[S <: SSys[S]](key: String, obj: Output[S], attr: NuagesAttribute[S])
                         (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute.Input[S] = {
    new NuagesOutputAttrInput[S](attr) // .init(obj)
  }
}
final class NuagesOutputAttrInput[S <: SSys[S]](val attribute: NuagesAttribute[S])
  extends NuagesAttribute.Input[S] {

  def value: Vec[Double] = ???

  def numChannels: Int = ???
}