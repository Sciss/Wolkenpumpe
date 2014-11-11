package de.sciss.synth
package ugen

import scala.collection.immutable.{IndexedSeq => Vec}

/** A graph element that controls the multi-channel expansion of
  * its `in` argument to match the `to` argument by padding (extending
  * and wrapping) it.
  *
  * @param in the element to replicate
  * @param to the reference element that controls the multi-channel expansion.
  *           the signal itself is not used or output by `Pad`.
  */
final case class Pad(in: GE, to: GE) extends UGenSource.SingleOut {
  def rate: MaybeRate = in.rate

  protected def makeUGens: UGenInLike = unwrap(Vector(in.expand, to.expand))

  protected def makeUGen(args: Vec[UGenIn]): UGenInLike = args.head
}