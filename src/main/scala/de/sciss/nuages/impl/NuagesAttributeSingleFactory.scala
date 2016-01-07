package de.sciss.nuages
package impl

import de.sciss.lucre.synth.Sys
import de.sciss.nuages.NuagesAttribute.Input

trait NuagesAttributeSingleFactory extends NuagesAttribute.Factory {
  final def tryConsume[S <: Sys[S]](oldInput: Input[S], newValue: Repr[S])
                                   (implicit tx: S#Tx, context: NuagesContext[S]): Option[Input[S]] = None
}
