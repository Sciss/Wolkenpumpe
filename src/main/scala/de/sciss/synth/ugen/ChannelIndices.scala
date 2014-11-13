package de.sciss.synth.ugen

import de.sciss.synth.{ScalarRated, UGenIn, UGenInLike, UGenSource, GE}

import scala.collection.immutable.{IndexedSeq => Vec}

/** A graph element that produces an integer sequence
  * from zero until the number-of-channels of the input element.
  *
  * @param in the element whose indices to produce
  */
final case class ChannelIndices(in: GE) extends UGenSource.SingleOut with ScalarRated {
  protected def makeUGens: UGenInLike = unwrap(in.expand.outputs)

  protected def makeUGen(args: Vec[UGenIn]): UGenInLike = 0 until args.size: GE
}