/*
 *  Util.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2018 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import java.text.{DateFormat, SimpleDateFormat}
import java.util.Locale

import de.sciss.file._
import de.sciss.lucre.artifact.{Artifact, ArtifactLocation}
import de.sciss.lucre.stm
import de.sciss.lucre.stm.Obj
import de.sciss.synth
import de.sciss.synth.GE
import de.sciss.synth.io.AudioFile
import de.sciss.synth.proc.AudioCue

object Util {
  /** Binary search on an indexed collection.
    *
    * @return  if positive: the position of elem in coll (i.e. elem is
    *          contained in coll). if negative: (-ins -1) where ins is the
    *          position at which elem should be inserted into the collection.
    */
  def binarySearch[A](coll: IndexedSeq[A], elem: A)(implicit ord: Ordering[A]): Int = {
    var index = 0
    var low = 0
    var high = coll.size - 1
    while ({
      index  = (high + low) >> 1
      low   <= high
    }) {
      val cmp = ord.compare(coll(index), elem)
      if (cmp == 0) return index
      if (cmp < 0) {
        low = index + 1
      } else {
        high = index - 1
      }
    }
    -low - 1
  }

  // ---- ScissProcs ----

  final val recFormatAIFF: DateFormat = new SimpleDateFormat("'rec_'yyMMdd'_'HHmmss'.aif'", Locale.US)

  final val attrRecArtifact = "$file"
  final val attrRecGenChans = "$gen-chans"
  final val attrRecDir      = "$rec-dir"

  def defaultRecDir: File = File.tempDir

  def findRecDir[S <: stm.Sys[S]](obj: Obj[S])(implicit tx: S#Tx): File =
    obj.attr.$[ArtifactLocation](attrRecDir).fold(defaultRecDir)(_.value)

  def getRecLocation[S <: stm.Sys[S]](n: Nuages[S], recDir: => File)(implicit tx: S#Tx): ArtifactLocation[S] = {
    val attr = n.attr
    attr.$[ArtifactLocation](Nuages.attrRecLoc).getOrElse {
      if (!recDir.exists()) tx.afterCommit(recDir.mkdirs())
      val newLoc = ArtifactLocation.newVar[S](recDir)
      // newLoc.name = RecName
      // root.modifiableOption.foreach(_.addLast(newLoc))
      attr.put(Nuages.attrRecLoc, newLoc)
      newLoc
    }
  }

  def wrapExtendChannels(n: Int, sig: GE): GE = Vector.tabulate(n)(sig.out)

  def mkLoop[S <: stm.Sys[S]](n: Nuages[S], art: Artifact[S], generatorChannels: Int)(implicit tx: S#Tx): Unit = {
    import synth._
    import ugen._

    val dsl = DSL[S]
    import dsl._
    val f       = art.value
    val spec    = AudioFile.readSpec(f)
    implicit val nuages: Nuages[S] = n

    def default(in: Double): ControlValues =
      if (generatorChannels <= 0)
        in
      else
        Vector.fill(generatorChannels)(in)

    def ForceChan(in: GE): GE = if (generatorChannels <= 0) in else {
      wrapExtendChannels(generatorChannels, in)
    }

    val procObj = generator(f.base) {
      val pSpeed      = pAudio  ("speed", ParamSpec(0.125, 2.3511, ExpWarp), default(1.0))
      val pStart      = pControl("start", ParamSpec(0, 1), default(0.0))
      val pDur        = pControl("dur"  , ParamSpec(0, 1), default(1.0))
      val bufId       = proc.graph.Buffer("file")
      val loopFrames  = BufFrames.kr(bufId)

      val numBufChans = spec.numChannels
      // val numChans    = if (generatorChannels > 0) generatorChannels else numBufChans

      val trig1       = LocalIn.kr(Pad(0, pSpeed)) // Pad.LocalIn.kr(pSpeed)
      val gateTrig1   = PulseDivider.kr(trig = trig1, div = 2, start = 1)
      val gateTrig2   = PulseDivider.kr(trig = trig1, div = 2, start = 0)
      val startFrame  = pStart *  loopFrames
      val numFrames   = pDur   * (loopFrames - startFrame)
      val lOffset     = Latch.kr(in = startFrame, trig = trig1)
      val lLength     = Latch.kr(in = numFrames , trig = trig1)
      val speed       = A2K.kr(pSpeed)
      val duration    = lLength / (speed * SampleRate.ir) - 2
      val gate1       = Trig1.kr(in = gateTrig1, dur = duration)
      val env         = Env.asr(2, 1, 2, Curve.lin) // \sin
      // val bufId       = Select.kr(pBuf, loopBufIds)
      val play1a      = PlayBuf.ar(numBufChans, bufId, speed, gateTrig1, lOffset, loop = 0)
      val play1b      = Mix(play1a)
      val play1       = ForceChan(play1b)
      // val play1       = Flatten(Seq.tabulate(numChans)(play1a \ _))
      val play2a      = PlayBuf.ar(numBufChans, bufId, speed, gateTrig2, lOffset, loop = 0)
      val play2b      = Mix(play2a)
      val play2       = ForceChan(play2b)
      // val play2       = Flatten(Seq.tabulate(numChans)(play2a \ _))
      val amp0        = EnvGen.kr(env, gate1) // 0.999 = bug fix !!!
      val amp2        = 1.0 - amp0.squared
      val amp1        = 1.0 - (1.0 - amp0).squared
      val sig         = (play1 * amp1) + (play2 * amp2)
      LocalOut.kr(Impulse.kr(1.0 / duration.max(0.1)))
      sig
    }
    // val art   = Artifact(locH(), f) // locH().add(f)
    val gr    = AudioCue.Obj[S](art, spec, 0L, 1.0)
    procObj.attr.put("file", gr) // Obj(AudioGraphemeElem(gr)))
    // val artObj  = Obj(ArtifactElem(art))
    // procObj.attr.put("file", artObj)
  }
}