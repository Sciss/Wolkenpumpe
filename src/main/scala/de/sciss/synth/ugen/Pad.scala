package de.sciss.synth
package ugen

import scala.collection.immutable.{IndexedSeq => Vec}

object Pad {
  object LocalIn {
    def ar(ref: GE): LocalIn = apply(audio  , ref)
    def kr(ref: GE): LocalIn = apply(control, ref)
  }
  final case class LocalIn(rate: Rate, ref: GE) extends GE.Lazy {
    override def productPrefix = "Pad$LocalIn"

    protected def makeUGens: UGenInLike = {
      val numChannels = ref.expand.flatOutputs.size
      ugen.LocalIn(rate, numChannels)
    }
  }

  /** Enforces multi-channel expansion for the input argument
    * even if it is passed into a vararg input of another UGen.
    * This is done by wrapping it in a `GESeq`.
    */
  def Split(in: GE): GE = GESeq(Vec(in))

//  final case class Split(in: GE) extends UGenSource.SingleOut {
//    def rate: MaybeRate = in.rate
//
//    protected def makeUGens: UGenInLike = unwrap(Vector(in.expand))
//
//    protected def makeUGen(args: Vec[UGenIn]): UGenInLike = args.head
//  }
}

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