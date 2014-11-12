package de.sciss.synth.ugen

import de.sciss.synth.{ScalarRated, UGenIn, UGenInLike, UGenSource, GE}

import scala.collection.immutable.{IndexedSeq => Vec}

/** A graph element that produces an integer with number-of-channels of the input element.
  *
  * @param in the element whose number-of-channels to produce
  */
final case class NumChannels(in: GE) extends UGenSource.SingleOut with ScalarRated {
  protected def makeUGens: UGenInLike = unwrap(Vector(in.expand))
  protected def makeUGen(args: Vec[UGenIn]): UGenInLike = Constant(args.size)
}