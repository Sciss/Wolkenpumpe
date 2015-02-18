/*
 *  ScissProcs.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2015 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import java.util.{Date, Locale}

import de.sciss.file._
import de.sciss.lucre.artifact.{Artifact, ArtifactLocation}
import de.sciss.lucre.{event => evt, stm}
import de.sciss.lucre.synth.Sys
import de.sciss.synth
import de.sciss.synth.io.AudioFile
import de.sciss.synth.proc.Action.Universe
import de.sciss.synth.proc.{SoundProcesses, ArtifactElem, Action, AuralSystem, AudioGraphemeElem, ExprImplicits, Grapheme, Obj}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.language.implicitConversions

/** This is my personal set of generators and filters. */
object ScissProcs {
  trait ConfigLike {
    //    def numLoops: Int
    //
    //    /** Maximum duration in seconds. */
    //    def loopDuration: Double

    def audioFilesFolder: Option[File]

    def lineInputs  : Vec[NamedBusConfig]
    def micInputs   : Vec[NamedBusConfig]
    def lineOutputs : Vec[NamedBusConfig]
    def masterGroups: Vec[NamedBusConfig]

    // set to zero to switch off
    def highPass: Int

    /** Force the number of channels in the generator, where `<= 0` indicates no forcing. */
    def generatorChannels: Int
  }

  object Config {
    implicit def build(b: ConfigBuilder): Config = b.build

    def apply(): ConfigBuilder = new ConfigBuilder
  }

  sealed trait Config extends ConfigLike

  final class ConfigBuilder private[ScissProcs]() extends ConfigLike {
    //    private var numLoopsVar: Int = 7
    //
    //    def numLoops: Int = numLoopsVar
    //
    //    def numLoops_=(n: Int): Unit = {
    //      require(n >= 0)
    //      numLoopsVar = n
    //    }
    //
    //    var loopDuration: Double = 30.0

    var audioFilesFolder: Option[File] = None

    var lineInputs  : Vec[NamedBusConfig] = Vec.empty
    var micInputs   : Vec[NamedBusConfig] = Vec.empty
    var lineOutputs : Vec[NamedBusConfig] = Vec.empty
    var masterGroups: Vec[NamedBusConfig] = Vec.empty

    var generatorChannels = 0

    var highPass = 0

    def build: Config = ConfigImpl(/* numLoops = numLoops, loopDuration = loopDuration, */
      audioFilesFolder = audioFilesFolder, lineInputs = lineInputs, micInputs = micInputs,
      lineOutputs = lineOutputs, masterGroups = masterGroups, generatorChannels = generatorChannels,
      highPass = highPass)
  }

  private case class ConfigImpl(// numLoops: Int, loopDuration: Double,
                                audioFilesFolder: Option[File],
                                lineInputs : Vec[NamedBusConfig], micInputs   : Vec[NamedBusConfig],
                                lineOutputs: Vec[NamedBusConfig], masterGroups: Vec[NamedBusConfig],
                                generatorChannels: Int, highPass: Int)
    extends Config

  var tapePath: Option[String] = None

  def apply[S <: Sys[S]](sConfig: ScissProcs.Config, nConfig: Nuages.Config)
                        (implicit tx: S#Tx, cursor: stm.Cursor[S], nuages: Nuages[S], aural: AuralSystem): Unit = {
    import synth._
    import ugen._

    val dsl = new DSL[S]
    import dsl._
    val imp = ExprImplicits[S]
    import imp._

    val masterChansOption = nConfig.masterChannels

    val loc   = ArtifactLocation[S](file(sys.props("java.io.tmpdir")))
    val locH  = tx.newHandle(loc)

    def ForceChan(in: GE): GE = if (sConfig.generatorChannels <= 0) in else {
      WrapExtendChannels(sConfig.generatorChannels, in)
    }

    // -------------- GENERATORS --------------

    //    generator("tape") {
    //      val pSpeed    = pAudio  ("speed", ParamSpec(0.1, 10, ExpWarp), default = 1)
    //      val pLoop     = pControl("loop" , ParamSpec(0, 1, LinWarp, 1), default = 0)
    //
    //      //      val pPos      = pScalar("pos"  , ParamSpec(0, 1), default = 0)
    //
    //      //      val path      = tapePath.getOrElse(sys.error("No audio-file selected"))
    //      //      val spec      = audioFileSpec(path)
    //      //      val numFrames = spec.numFrames
    //      //      val startPos  = pPos.v
    //      //      val startFr   = ((if (startPos < 1) startPos else 0.0) * numFrames).toLong
    //      //      val b         = bufCue(path, startFrame = startFr)
    //      //      val numCh     = b.numChannels
    //      val speed     = A2K.kr(pSpeed) // * BufRateScale.ir(b.id)
    //
    //      val disk      = proc.graph.VDiskIn.ar("tape", speed = speed, loop = pLoop, maxSpeed = 10)
    //
    //      //      val lp0       = pLoop.v
    //      //
    //      //      // pos feedback
    //      //      val framesRead = Integrator.kr(speed) * (SampleRate.ir / ControlRate.ir)
    //
    //      //      val me = Proc.local
    //      //      Impulse.kr( 10 ).react( framesRead ) { smp => ProcTxn.spawnAtomic { implicit tx =>
    //      //        val frame  = startFr + smp( 0 )
    //      //        // not sure we can access them in this scope, so just retrieve the controls...
    //      //        val ppos   = me.control( "pos" )
    //      //        //               val ploop  = me.control( "loop" )
    //      //        if( lp0 == 1 ) {
    //      //          ppos.v = (frame % numFrames).toDouble / numFrames
    //      //        } else {
    //      //          val pos = (frame.toDouble / numFrames).min( 1.0 )
    //      //          ppos.v = pos
    //      //          if( pos == 1.0 ) me.stop
    //      //        }
    //      //      }}
    //
    //      // val disk = VDiskIn.ar(numCh, b.id, speed, loop = pLoop)
    //      disk // WrapExtendChannels( 2, disk )
    //    }

    sConfig.audioFilesFolder.foreach { folder =>
      val loc = ArtifactLocation[S](folder)

      def abbreviate(s: String) = if (s.length < 16) s else s"${s.take(7)}...${s.takeRight(7)}"

      val audioFiles = folder.children
      audioFiles.filter(AudioFile.identify(_).isDefined).foreach(f => {
        val name = s"t-${abbreviate(f.base)}"

        val procObj = generator(name) {
          val p1    = pAudio("speed", ParamSpec(0.1f, 10f, ExpWarp), 1)
          val speed = p1
          val disk  = proc.graph.VDiskIn.ar("file", speed = speed, loop = 1)
          //          val b     = bufCue(path)
          //          val disk  = VDiskIn.ar(b.numChannels, b.id, p1.ar * BufRateScale.ir(b.id), loop = 1)
          // HPF.ar( disk, 30 )
          val sig = ForceChan(disk)
          sig
        }

        val art   = loc.add(f)
        val spec  = AudioFile.readSpec(f)
        val gr    = Grapheme.Expr.Audio(art, spec, 0L, 1.0)
        procObj.attr.put("file", Obj(AudioGraphemeElem(gr)))
      })
    }

    masterChansOption.foreach { masterChans =>
      val numChans = masterChans.size

      generator("(test)") {
        val pAmp  = pControl("amp" , ParamSpec(0.01,  1.0, ExpWarp),  default = 1)
        val pSig  = pControl("sig" , /* ParamSpec(0, 2, IntWarp) */ TrigSpec, default = 0)
        val pFreq = pControl("freq", ParamSpec(0.1 , 10  , ExpWarp),  default = 1)

        val idx       = Stepper.kr(Impulse.kr(pFreq), lo = 0, hi = numChans)
        val sig0: GE  = Seq(WhiteNoise.ar(1), SinOsc.ar(441))
        val sig       = Select.ar(pSig, sig0) * pAmp
        val sigOut: GE = Seq.tabulate(numChans)(ch => sig * (1 - (ch - idx).abs.min(1)))
        sigOut
      }
    }

    // val loopFrames  = (sConfig.loopDuration * 44100 /* config.server.sampleRate */).toInt
    //    val loopBuffers = Vec.fill[Buffer](config.numLoops)(Buffer.alloc(config.server, loopFrames, 2))
    //    val loopBufIDs  = loopBuffers.map(_.id)

    //    if (settings.numLoops > 0) {

    def mkLoop(f: File)(implicit tx: S#Tx): Unit = {
      val procObj = generator(f.base) {
        val pSpeed      = pAudio  ("speed", ParamSpec(0.125, 2.3511, ExpWarp), default = 1)
        val pStart      = pControl("start", ParamSpec(0, 1), default = 0)
        val pDur        = pControl("dur"  , ParamSpec(0, 1), default = 1)
        val bufID       = proc.graph.Buffer("file")
        val loopFrames  = BufFrames.kr(bufID)

        val trig1       = LocalIn.kr(1)
        val gateTrig1   = PulseDivider.kr(trig = trig1, div = 2, start = 1)
        val gateTrig2   = PulseDivider.kr(trig = trig1, div = 2, start = 0)
        val startFrame  = pStart * loopFrames
        val numFrames   = pDur * (loopFrames - startFrame)
        val lOffset     = Latch.kr(in = startFrame, trig = trig1)
        val lLength     = Latch.kr(in = numFrames, trig = trig1)
        val speed       = A2K.kr(pSpeed)
        val duration    = lLength / (speed * SampleRate.ir) - 2
        val gate1       = Trig1.kr(in = gateTrig1, dur = duration)
        val env         = Env.asr(2, 1, 2, Curve.lin) // \sin
        // val bufID       = Select.kr(pBuf, loopBufIDs)
        val play1       = PlayBuf.ar(2, bufID, speed, gateTrig1, lOffset, loop = 0) // XXX TODO - numChannels
        val play2       = PlayBuf.ar(2, bufID, speed, gateTrig2, lOffset, loop = 0)
        val amp0        = EnvGen.kr(env, gate1) // 0.999 = bug fix !!!
        val amp2        = 1.0 - amp0.squared
        val amp1        = 1.0 - (1.0 - amp0).squared
        val sig         = (play1 * amp1) + (play2 * amp2)
        LocalOut.kr(Impulse.kr(1.0 / duration.max(0.1)))
        sig
      }
      val art   = locH().add(f)
      val spec  = AudioFile.readSpec(f)
      val gr    = Grapheme.Expr.Audio(art, spec, 0L, 1.0)
      procObj.attr.put("file", Obj(AudioGraphemeElem(gr)))
      // val artObj  = Obj(ArtifactElem(art))
      // procObj.attr.put("file", artObj)
    }

    //      masterBusOption.foreach { masterBus =>
    //        gen( "sum_rec" ) {
    //          val pbuf    = pControl( "buf",  ParamSpec( 0, settings.numLoops - 1, LinWarp, 1 ), 0 )
    //          val pfeed   = pControl( "feed", ParamSpec( 0, 1 ), 0 )
    //          val ploop   = pScalar( "loop", ParamSpec( 0, 1, LinWarp, 1 ), 0 )
    //          /* val ppos = */ pControl( "pos", ParamSpec( 0, 1 ), 0 )
    //          graph {
    //            val in      = InFeedback.ar( masterBus.index, masterBus.numChannels )
    //            val w       = 2.0 / in.numChannels // numOutputs
    //            val sig     = SplayAz.ar( 2, in )
    //
    //            val sig1    = LeakDC.ar( Limiter.ar( sig /* .toSeq */ * w ))
    //            val bufID   = Select.kr( pbuf.kr, loopBufIDs )
    //            val feed    = pfeed.kr
    //            val prelvl  = feed.sqrt
    //            val reclvl  = (1 - feed).sqrt
    //            val loop    = ploop.ir
    //            val rec     = RecordBuf.ar( sig1, bufID, recLevel = reclvl, preLevel = prelvl, loop = loop )
    //
    //            // pos feedback
    //            val bufFr   = BufFrames.kr( bufID )
    //            val pos     = Phasor.kr( 1, SampleRate.ir/ControlRate.ir, 0, bufFr * 2 ) / bufFr // BufDur.kr( bufID ).reciprocal
    //            val me      = Proc.local
    //            val lp0     = ploop.v
    //            Impulse.kr( 10 ).react( pos ) { smp => ProcTxn.spawnAtomic { implicit tx =>
    //              val pos0 = smp( 0 )
    //              // not sure we can access them in this scope, so just retrieve the controls...
    //              val ppos = me.control( "pos" )
    //              ppos.v   = if( lp0 == 1 ) (pos0 % 1.0) else pos0.min( 1.0 )
    //            }}
    //
    //            Done.kr( rec ).react { ProcTxn.spawnAtomic { implicit tx => me.stop }}
    //
    //            Silent.ar( 2 )// dummy thru
    //          }
    //        }
    //      }
    //    }

    sConfig.micInputs.foreach { cfg =>
      generator(cfg.name) {
        val pBoost    = pAudio("gain", ParamSpec(0.1, 10, ExpWarp), default = 0.1 /* 1 */)
        val pFeed     = pAudio("feed", ParamSpec(0, 1), default = 0)

        val boost     = pBoost
        val pureIn    = In.ar(NumOutputBuses.ir + cfg.offset, cfg.numChannels) * boost
        val bandFrequencies = List(150, 800, 3000)
        val ins       = HPZ1.ar(pureIn) // .outputs
        var outs: GE = 0
        var flt: GE = ins
        bandFrequencies.foreach { maxFreq =>
          val band = if (maxFreq != bandFrequencies.last) {
            val res = LPF.ar(flt, maxFreq)
            flt     = HPF.ar(flt, maxFreq)
            res
          } else {
            flt
          }
          val amp   = Amplitude.kr(band, 2, 2)
          val slope = Slope.kr(amp)
          val comp  = Compander.ar(band, band, 0.1, 1, slope.max(1).reciprocal, 0.01, 0.01)
          outs      = outs + comp
        }
        val dly   = DelayC.ar(outs, 0.0125, LFDNoise1.kr(5).madd(0.006, 0.00625))
        val feed  = pFeed * 2 - 1
        val sig   = XFade2.ar(pureIn, dly, feed)

        // val numOut = masterChansOption.fold(2)(_.size)
        val numOut = if (sConfig.generatorChannels <= 0) masterChansOption.fold(2)(_.size) else sConfig.generatorChannels

        val sig1: GE = if (numOut == cfg.numChannels) {
            sig
          } else if (cfg.numChannels == 1) {
            Seq.fill[GE](numOut)(sig)
          } else {
            SplayAz.ar(numOut, sig)
          }
        sig1
      }
    }

    sConfig.lineInputs.foreach { cfg =>
      generator(cfg.name) {
        val pBoost = pAudio("gain", ParamSpec(0.1, 10, ExpWarp), default = 1)

        val boost   = pBoost
        val sig     = In.ar(NumOutputBuses.ir + cfg.offset, cfg.numChannels) * boost
        // val numOut  = masterChansOption.fold(2)(_.size)
        val numOut  = if (sConfig.generatorChannels <= 0) masterChansOption.fold(2)(_.size) else sConfig.generatorChannels

        val sig1: GE = if (numOut == cfg.numChannels) {
            sig
          } else if (cfg.numChannels == 1) {
            Seq.fill[GE](numOut)(sig)
          } else {
            SplayAz.ar(numOut, sig)
          }
        sig1
      }
    }

    // -------------- SIGNAL GENERATORS --------------

    generator("~dust") {
      val pFreq   = pAudio("freq" , ParamSpec(0.01, 1000, ExpWarp), default = 0.1 /* 1 */)
      val pDecay  = pAudio("decay", ParamSpec(0.1 ,   10, ExpWarp), default = 0.1 /* 1 */)

      val freq0 = pFreq
      val freq  = ForceChan(freq0)
      Decay.ar(Dust.ar(freq), pDecay)
    }

    generator("~gray") {
      val pAmp  = pAudio("amp", ParamSpec(0.01, 1, ExpWarp), default = 0.1)
      val amp0  = pAmp
      val amp   = ForceChan(amp0)
      GrayNoise.ar(amp)
    }

    generator("~sin") {
      val pFreq1  = pAudio("freq"     , ParamSpec(0.1 , 10000, ExpWarp), default = 15 /* 1 */)
      val pFreq2  = pAudio("freq-fact", ParamSpec(0.01,   100, ExpWarp), default =  1 /* 1 */)
      val pAmp    = pAudio("amp"      , ParamSpec(0.01,     1, ExpWarp), default =  0.1)

      val numOut  = if (sConfig.generatorChannels <= 0) masterChansOption.fold(2)(_.size) else sConfig.generatorChannels

      // val f1 = pFreq1
      // val f2 = f1 * pFreq2
      val freq = Vec.tabulate(numOut) { ch =>
        val w = (ch: GE).linlin(0, (numOut - 1).max(1), 1, pFreq2)
        (pFreq1 * w).clip(0.01, 20000)
      }
      SinOsc.ar(freq) * pAmp
    }

    generator("~pulse") {
      val pFreq1  = pAudio("freq"     , ParamSpec(0.1 , 10000, ExpWarp), default = 15 /* 1 */)
      val pFreq2  = pAudio("freq-fact", ParamSpec(0.01,   100, ExpWarp), default =  1 /* 1 */)
      val pw1     = pAudio("width1"   , ParamSpec(0.0 ,     1.0),        default =  0.5)
      val pw2     = pAudio("width2"   , ParamSpec(0.0 ,     1.0),        default =  0.5)
      val pAmp    = pAudio("amp"      , ParamSpec(0.01,     1, ExpWarp), default =  0.1)

      //      val f1 = pFreq1
      //      val f2 = f1 * pFreq2
      //      val w1 = pw1
      //      val w2 = pw2

      // val numOut = masterChansOption.fold(2)(_.size)
      val numOut  = if (sConfig.generatorChannels <= 0) masterChansOption.fold(2)(_.size) else sConfig.generatorChannels

      val sig: GE = Vec.tabulate(numOut) { ch =>
        val freqM = (ch: GE).linlin(0, (numOut - 1).max(1), 1, pFreq2)
        val freq  = (pFreq1 * freqM).clip(0.01, 20000)
        val width = (ch: GE).linlin(0, (numOut - 1).max(1), pw1, pw2)
        Pulse.ar(freq, width)
      }

      // Pulse.ar(f1 :: f2 :: Nil, w1 :: w2 :: Nil) * pAmp
      sig * pAmp
    }

    // -------------- FILTERS --------------

    def mix(in: GE, flt: GE, mix: GE): GE = LinXFade2.ar(in, flt, mix * 2 - 1)
    def mkMix(): GE = pAudio("mix", ParamSpec(0, 1), default = 0)

    def WrapExtendChannels(n: Int, sig: GE): GE = Vec.tabulate(n)(sig \ _)

    //    if( settings.numLoops > 0 ) {
    //      filter( ">rec" ) {
    //        val pbuf    = pControl( "buf",  ParamSpec( 0, settings.numLoops - 1, LinWarp, 1 ), 0 )
    //        val pfeed   = pControl( "feed", ParamSpec( 0, 1 ), 0 )
    //        val ploop   = pScalar( "loop", ParamSpec( 0, 1, LinWarp, 1 ), 0 )
    //        /* val ppos = */ pScalar( "pos", ParamSpec( 0, 1 ), 0 )
    //        graph { in: In =>
    //          val bufID   = Select.kr( pbuf.kr, loopBufIDs )
    //          val feed    = pfeed.kr
    //          val prelvl  = feed.sqrt
    //          val reclvl  = (1 - feed).sqrt
    //          val loop    = ploop.ir
    //          val sig     = LeakDC.ar( Limiter.ar( in ))
    //          val rec     = RecordBuf.ar( sig /* in */, bufID, recLevel = reclvl, preLevel = prelvl, loop = loop )
    //
    //          // pos feedback
    //          //            val pos     = Line.kr( 0, 1, BufDur.kr( bufID ))
    //          val bufFr   = BufFrames.kr( bufID )
    //          val pos     = Phasor.kr( 1, SampleRate.ir/ControlRate.ir, 0, bufFr * 2 ) / bufFr // BufDur.kr( bufID ).reciprocal
    //        val me      = Proc.local
    //          val lp0     = ploop.v
    //          Impulse.kr( 10 ).react( pos ) { smp => ProcTxn.spawnAtomic { implicit tx =>
    //            val pos0 = smp( 0 )
    //            // not sure we can access them in this scope, so just retrieve the controls...
    //            val ppos = me.control( "pos" )
    //            ppos.v   = if( lp0 == 1 ) (pos0 % 1.0) else pos0.min( 1.0 )
    //          }}
    //
    //          Done.kr( rec ).react { ProcTxn.spawnAtomic { implicit tx => me.bypass }}
    //
    //          in  // dummy thru
    //        }
    //      }
    //    }

    filter("staub") { in =>
      val pAmt      = pAudio("amt" , ParamSpec(0.0, 1.0), default = 1.0)
      val pFact     = pAudio("fact", ParamSpec(0.5, 2.0, ExpWarp), default = 1)
      val pMix      = mkMix()

      val f1        =   10
      val f2        = 2000

      val relIdx    = ChannelIndices(in) / (NumChannels(in) - 1).max(1)
      val fade      = (pAmt * relIdx.linlin(0, 1, 1, pFact)).clip(0, 1)
      val dustFreqS = fade.linexp(0, 1, f1, f2)
      // val dustFreqP = fade.linexp(1, 0, f1, f2)

      val decayTime = 0.01
      val dustS     = Decay.ar(Dust.ar(dustFreqS), decayTime).min(1)
      // val dustP = Decay.ar(Dust.ar(dustFreqP), decayTime).min(1)

      val flt       = in * dustS

      mix(in, flt, pMix)
    }

    filter("delay") { in =>
      val pTime       = pAudio("time", ParamSpec(0.03, 30.0, ExpWarp), default = 10)
      val pFeed       = pAudio("feed", ParamSpec(0.001, 1.0, ExpWarp), default = 0.001)
      val pMix        = mkMix()

      val numFrames   = SampleRate.ir * 30
      val buf         = LocalBuf(numFrames = numFrames, numChannels = Pad(1, in))
      val time        = Lag.ar(pTime)
      val lin         = Pad.LocalIn.ar(in)
      val feed        = pFeed
      val wDry        = (1 - feed).sqrt
      val wWet        = feed.sqrt
      val flt0        = BufDelayL.ar(buf, (in * wDry) + (lin * wWet), time)
      val flt         = LeakDC.ar(flt0)
      LocalOut.ar(flt)

      mix(in, flt, pMix)
    }

    filter("mantissa") { in =>
      val pBits = pAudio("bits", ParamSpec(2, 14, IntWarp), default = 14)
      val pMix  = mkMix()

      val flt = MantissaMask.ar(in, pBits)
      mix(in, flt, pMix)
    }

    filter("achil") { in =>
      shortcut = "A"
      val pSpeed  = pAudio("speed", ParamSpec(0.125, 2.3511, ExpWarp), default = 0.5)
      val pMix    = mkMix()

      val speed       = Lag.ar(pSpeed, 0.1)
      val numFrames   = SampleRate.ir // sampleRate.toInt
      val bufID       = LocalBuf(numFrames = numFrames, numChannels = Pad(1, in))
      val writeRate   = BufRateScale.kr(bufID)
      val readRate    = writeRate * speed
      val readPhasor  = Phasor.ar(0, readRate, 0, numFrames)
      val read0       = BufRd.ar(1, bufID, readPhasor, 0, 4)
      val readBad     = CheckBadValues.ar(read0, id = 1000)
      val read        = Gate.ar(read0, readBad sig_== 0)

      val writePhasor = Phasor.ar(0, writeRate, 0, numFrames)
      val old         = BufRd.ar(1, bufID, writePhasor, 0, 1)
      val wet0        = SinOsc.ar(0, (readPhasor - writePhasor).abs / numFrames * math.Pi)
      val dry         = 1 - wet0.squared
      val wet         = 1 - (1 - wet0).squared
      val write0      = (old * dry) + (in * wet)
      val writeBad    = CheckBadValues.ar(write0, id = 1001)
      val writeSig    = Gate.ar(write0, writeBad sig_== 0)

      // NOTE: `writeSig :: Nil: GE` does _not_ work because single
      // element seqs are not created by that conversion.
      BufWr.ar(Pad.Split(writeSig), bufID, writePhasor)
      mix(in, read, pMix)
    }

    filter("a-gate") { in =>
      val pAmt = pAudio("amt", ParamSpec(0, 1), default = 1)
      val pMix = mkMix()

      val amount  = Lag.ar(pAmt, 0.1)
      val flt     = Compander.ar(in, in, Amplitude.ar(in * (1 - amount) * 5), 20, 1, 0.01, 0.001)
      mix(in, flt, pMix)
    }

    filter("a-hilb") { in =>
      val pMix = mkMix()
      val hlb   = Hilbert.ar(DelayN.ar(in, 0.01, 0.01))
      val hlb2  = Hilbert.ar(Normalizer.ar(in, dur = 0.02))
      val flt   = hlb.real * hlb2.real - hlb.imag * hlb2.imag
      mix(in, flt, pMix)
    }

    filter("hilbert") { in =>
      shortcut = "H"
      val pFreq = pAudio("freq", ParamSpec(-1, 1), 0.0)
      val pMix  = mkMix()

      val freq    = pFreq
      val freqHz  = freq.abs.linexp(0, 1, 20, 12000) * freq.signum
      val flt     = FreqShift.ar(in, freqHz)
      mix(in, flt, pMix)
    }

    filter("reso") { in =>
      shortcut = "R"
      val pFreq   = pAudio("freq"     , ParamSpec(30  , 13000, ExpWarp), default = 400) // beware of the upper frequency
      val pFreq2  = pAudio("freq-fact", ParamSpec( 0.5,     2, ExpWarp), default =   1)
      val pq      = pAudio("q"        , ParamSpec( 0.5,    50, ExpWarp), default =   1)
      val pMix    = mkMix()

      val freq0   = pFreq
      val freq    = freq0 :: (freq0 * pFreq2).max(30).min(13000) :: Nil
      val rq      = pq.reciprocal
      val makeUp  = pq // (rq + 0.5).pow(1.41) // rq.max( 1 ) // .sqrt
      val flt     = Resonz.ar(in, freq, rq) * makeUp
      mix(in, flt, pMix)
    }

    filter("notch") { in =>
      shortcut = "N"
      val pFreq   = pAudio("freq", ParamSpec(30, 16000, ExpWarp), default = 400)
      val pFreq2  = pAudio("freq-fact", ParamSpec(0.5, 2, ExpWarp), default = 1)
      val pq      = pAudio("q", ParamSpec(1, 50, ExpWarp), default = 1) // beware of the lower q
      val pMix    = mkMix()

      val freq0   = pFreq
      val freq    = freq0 :: (freq0 * pFreq2).max(30).min(16000) :: Nil
      val rq      = pq.reciprocal
      val flt     = BRF.ar(in, freq, rq)
      mix(in, flt, pMix)
    }

    filter("filt") { in =>
      shortcut = "F"
      val pFreq = pAudio("freq", ParamSpec(-1, 1), default = 0.54)
      val pMix  = mkMix()

      val normFreq  = pFreq
      val lowFreqN  = Lag.ar(Clip.ar(normFreq, -1, 0))
      val highFreqN = Lag.ar(Clip.ar(normFreq, 0, 1))
      val lowFreq   = LinExp.ar(lowFreqN, -1, 0, 30, 20000)
      val highFreq  = LinExp.ar(highFreqN, 0, 1, 30, 20000)
      val lowMix    = Clip.ar(lowFreqN * -10.0, 0, 1)
      val highMix   = Clip.ar(highFreqN * 10.0, 0, 1)
      val dryMix    = 1 - (lowMix + highMix)
      val lpf       = LPF.ar(in, lowFreq) * lowMix
      val hpf       = HPF.ar(in, highFreq) * highMix
      val dry       = in * dryMix
      val flt       = dry + lpf + hpf
      mix(in, flt, pMix)
    }

    filter("frgmnt") { in =>
      val pSpeed      = pAudio  ("speed", ParamSpec(0.125, 2.3511, ExpWarp), default = 1)
      val pGrain      = pControl("grain", ParamSpec(0, 1), default = 0.5)
      val pFeed       = pAudio  ("fb"   , ParamSpec(0, 1), default = 0)
      val pMix        = mkMix()

      val bufDur      = 4.0
      val numFrames   = bufDur * SampleRate.ir
      //      val numChannels = in.numChannels // numOutputs
      //      val buf         = bufEmpty(numFrames, numChannels)
      //      val bufID       = buf.id
      val buf         = LocalBuf(numFrames = numFrames, numChannels = Pad(1, in))

      val feedBack    = Lag.ar(pFeed, 0.1)
      val grain       = pGrain // Lag.kr( grainAttr.kr, 0.1 )
      val maxDur      = LinExp.kr(grain, 0, 0.5, 0.01, 1.0)
      val minDur      = LinExp.kr(grain, 0.5, 1, 0.01, 1.0)
      val fade        = LinExp.kr(grain, 0, 1, 0.25, 4)
      val rec         = (1 - feedBack).sqrt
      val pre         = feedBack.sqrt
      val trig        = LocalIn.kr(1)
      val white       = TRand.kr(0, 1, trig)
      val dur         = LinExp.kr(white, 0, 1, minDur, maxDur)
      val off0        = numFrames * white
      val off         = off0 - (off0 % 1.0)
      val gate        = trig
      val lFade       = Latch.kr(fade, trig)
      val fadeIn      = lFade * 0.05
      val fadeOut     = lFade * 0.15
      val env         = EnvGen.ar(Env.linen(fadeIn, dur, fadeOut, 1, Curve.sine), gate, doneAction = 0)
      val recLevel0   = env.sqrt
      val preLevel0   = (1 - env).sqrt
      val recLevel    = recLevel0 * rec
      val preLevel    = preLevel0 * (1 - pre) + pre
      // val run         = recLevel > 0
      //      run     .poll(1, "run"      )
      //      recLevel.poll(1, "rec-level")
      // val ins = Pad.Split(in)
      // ins.poll(1, "ins")
      // RecordBuf.ar(ins, buf = buf, offset = off, recLevel = recLevel, preLevel = preLevel, run = run, loop = 1)
      val writeIdx    = Phasor.ar(speed = 1, lo = 0, hi = numFrames)
      val preSig      = BufRd.ar(1, buf = buf, index = (writeIdx + off) % numFrames, loop = 1)
      val write0      = in * recLevel + preSig * preLevel
      val writeBad    = CheckBadValues.ar(write0, id = 2001)
      val writeSig    = Gate.ar(write0, writeBad sig_== 0)

      // writeSig.poll(1, "write")
      BufWr.ar(Pad.Split(writeSig), buf = buf, index = writeIdx, loop = 1)
      LocalOut.kr(Impulse.kr(1.0 / (dur + fadeIn + fadeOut).max(0.01)))

      // NOTE: PlayBuf doesn't seem to work with LocalBuf !!!

      val speed       = pSpeed
      // val play0       = PlayBuf.ar(1 /* numChannels */, buf, speed, loop = 1)
      val readIdx     = Phasor.ar(speed = speed, lo = 0, hi = numFrames)
      val read0       = BufRd.ar(1, buf = buf, index = readIdx, loop = 1)
      val readBad     = CheckBadValues.ar(read0, id = 2000)
      val play0       = Gate.ar(read0, readBad sig_== 0)
      val play        = Flatten(play0)
      // play.poll(1, "outs")
      mix(in, play, pMix)
    }

    //    filter("*") { in =>
    //      val pmix = mkMix
    //      val bin2 = pAudioIn("in2")
    //
    //      val in2 = bin2.ar
    //      val flt = in * in2
    //      mix(in, flt, pmix)
    //    }

    filter("gain") { in =>
      shortcut = "G"
      val pGain = pAudio("gain", ParamSpec(-30, 30), default = 0)
      val pMix  = mkMix()

      val amp = pGain.dbamp
      val flt = in * amp
      mix(in, flt, pMix)
    }

    filter("gendy") { in =>
      val pAmt    = pAudio("amt", ParamSpec(0, 1), default = 1)
      val pMix    = mkMix()

      val amt     = Lag.ar(pAmt, 0.1)
      val minFreq = amt * 69 + 12
      val scale   = amt * 13 + 0.146
      val gendy   = Gendy1.ar(2, 3, 1, 1,
        minFreq   = minFreq,
        maxFreq   = minFreq * 8,
        ampScale  = scale,
        durScale  = scale,
        initCPs   = 7,
        kNum      = 7) * in
      val flt = Compander.ar(gendy, gendy, 0.7, 1, 0.1, 0.001, 0.02)
      mix(in, flt, pMix)
    }

    filter("~skew") { in =>
      val pLo     = pAudio("lo" , ParamSpec(0, 1), default = 0)
      val pHi     = pAudio("hi" , ParamSpec(0, 1), default = 1)
      val pPow    = pAudio("pow", ParamSpec(0.125, 8, ExpWarp), default = 1)
      val pRound  = pAudio("rnd", ParamSpec(0, 1), default = 0)

      val pMix    = mkMix()

      val sig = in.clip2(1).linlin(-1, 1, pLo, pHi).pow(pPow).roundTo(pRound) * 2 - 1
      mix(in, sig, pMix)
    }

    filter("~onsets") { in =>
      val pThresh     = pControl("thresh", ParamSpec(0, 1), default = 0.5)
      val pDecay      = pAudio  ("decay" , ParamSpec(0, 1), default = 0)

      val pMix        = mkMix()

      //      val numChannels = in.numChannels // numOutputs
      //      val bufIDs      = Seq.fill(numChannels)(bufEmpty(1024).id)
      val bufIDs      = LocalBuf(numFrames = 1024, numChannels = Pad(1, in))
      val chain1      = FFT(bufIDs, in)
      val onsets      = Onsets.kr(chain1, pThresh)
      val sig         = Decay.ar(Trig1.ar(onsets, SampleDur.ir), pDecay).min(1) // * 2 - 1
      mix(in, sig, pMix)
    }

    filter("m-above") { in =>
      val pThresh = pAudio("thresh", ParamSpec(1.0e-3, 1.0e-0, ExpWarp), 1.0e-2)
      val pMix    = mkMix()

      val thresh  = A2K.kr(pThresh)
      val env     = Env(0.0, Seq(Env.Segment(0.2, 0.0, Curve.step), Env.Segment(0.2, 1.0, Curve.lin)))
      val ramp    = EnvGen.kr(env)
      val volume  = thresh.linlin(1.0e-3, 1.0e-0, 4 /* 32 */, 2)
      val bufIDs  = LocalBuf(numFrames = 1024, numChannels = Pad(1, in))
      val chain1  = FFT(bufIDs, HPZ1.ar(in))
      val chain2  = PV_MagAbove(chain1, thresh)
      val flt     = LPZ1.ar(volume * IFFT.ar(chain2)) * ramp

      // account for initial dly
      val bufDur  = 1024 / SampleRate.ir
      val env2    = Env(0.0, Seq(Env.Segment(bufDur * 2, 0.0, Curve.step), Env.Segment(0.2, 1, Curve.lin)))
      val wet     = EnvGen.kr(env2)
      val sig     = (in * (1 - wet).sqrt) + (flt * wet)
      mix(in, sig, pMix)
    }

    filter("m-below") { in =>
      val pThresh     = pAudio("thresh", ParamSpec(1.0e-1, 10.0, ExpWarp), default = 1.0)
      val pMix        = mkMix()

      val thresh      = A2K.kr(pThresh)
      val env         = Env(0.0, Seq(Env.Segment(0.2, 0.0, Curve.step), Env.Segment(0.2, 1.0, Curve.lin)))
      val ramp        = EnvGen.kr(env)
      //            val volume		   = LinLin.kr( thresh, 1.0e-2, 1.0e-0, 4, 1 )
      val volume      = thresh.linlin(1.0e-1, 10, 2, 1)
      val bufIDs      = LocalBuf(numFrames = 1024, numChannels = Pad(1, in))
      val chain1      = FFT(bufIDs, in)
      val chain2      = PV_MagBelow(chain1, thresh)
      val flt         = volume * IFFT.ar(chain2) * ramp

      // account for initial dly
      val env2 = Env(0.0, Seq(Env.Segment(BufDur.kr(bufIDs) * 2, 0.0, Curve.step), Env.Segment(0.2, 1, Curve.lin)))
      val wet = EnvGen.kr(env2)
      val sig = (in * (1 - wet).sqrt) + (flt * wet)
      mix(in, sig, pMix)
    }

    filter("pitch") { in =>
      val pTrans  = pAudio("shift", ParamSpec(0.125, 4, ExpWarp), 1)
      val pTime   = pAudio("time", ParamSpec(0.01, 1, ExpWarp), 0.1)
      val pPitch  = pAudio("pitch", ParamSpec(0.01, 1, ExpWarp), 0.1)
      val pMix    = mkMix()

      val grainSize     = 0.5f
      val pitch         = A2K.kr(pTrans)
      val timeDisperse  = A2K.kr(pTime )
      val pitchDisperse = A2K.kr(pPitch)
      val flt           = PitchShift.ar(in, grainSize, pitch, pitchDisperse, timeDisperse * grainSize)
      mix(in, flt, pMix)
    }

    filter("pow") { in =>
      val pAmt = pAudio("amt", ParamSpec(0, 1), default = 0.5)
      val pMix = mkMix()

      val amt   = pAmt
      val amtM  = 1 - amt
      val exp   = amtM * 0.5 + 0.5
      val flt0  = in.abs.pow(exp) * in.signum
      val amp0  = Amplitude.ar(flt0)
      val amp   = amtM + (amp0 * amt)
      val flt   = flt0 * amp
      mix(in, flt, pMix)
    }

    filter("renoise") { in =>
      val pColor      = pAudio("color", ParamSpec(0, 1), default = 0)
      val pMix        = mkMix()
      val step        = 0.5
      val freqF       = math.pow(2, step)
      val frequencies = Vec.iterate(32.0, 40)(_ * freqF).filter(_ <= 16000)

      val color       = Lag.ar(pColor, 0.1)
      val noise       = WhiteNoise.ar(Pad(1, in))
      val sig         = frequencies.foldLeft[GE](0) { (sum, freq) =>
        val flt   = BPF.ar(in, freq, step)
        val freq2 = ZeroCrossing.ar(flt)
        val w0    = Amplitude.ar(flt)
        val w2    = w0 * color
        val w1    = w0 * (1 - color)
        sum + BPF.ar((noise * w1) + (LFPulse.ar(freq2) * w2), freq, step)
      }
      val amp         = step.reciprocal // compensate for Q
      val flt         = sig * amp
      mix(in, flt, pMix)
    }

    filter("verb") { in =>
      val pExtent = pControl("size" , ParamSpec(0, 1), default = 0.5)
      val pColor  = pControl("color", ParamSpec(0, 1), default = 0.5)
      val pMix    = mkMix()

      val extent      = pExtent
      val color       = Lag.kr(pColor, 0.1)
      val i_roomSize  = LinExp.kr(extent, 0, 1, 1, 100)
      val i_revTime   = LinExp.kr(extent, 0, 1, 0.3, 20)
      val spread      = 15
      //      val numChannels = in.numOutputs
      //      val ins         = in.outputs
      //      val verbs       = (ins :+ ins.last).grouped(2).toSeq.flatMap(pair =>
      //        (GVerb.ar(Mix(pair), i_roomSize, i_revTime, color, color, spread, 0, 1, 0.7, i_roomSize) * 0.3).outputs
      //      )
      //      val flt: GE = Vector(verbs.take(numChannels): _*) // drops last one if necessary
      val fltS        = GVerb.ar(in, i_roomSize, i_revTime, color, color, spread, 0, 1, 0.7, i_roomSize) * 0.3
      val flt         = fltS \ 0   // simply drop the right channels of each verb
      mix(in, flt, pMix)
    }

    filter("zero") { in =>
      val pWidth  = pAudio("width", ParamSpec(0, 1), default = 0.5)
      val pDiv    = pAudio("div"  , ParamSpec(1, 10, IntWarp), default = 1)
      val pLag    = pAudio("lag"  , ParamSpec(0.001, 0.1, ExpWarp), default = 0.01)
      val pMix    = mkMix()

      val freq    = ZeroCrossing.ar(in).max(20)
      val width0  = Lag.ar(pWidth, 0.1)
      val amp     = width0.sqrt
      val width   = width0.reciprocal
      val div     = Lag.ar(pDiv, 0.1)
      val lagTime = pLag
      val pulse   = Lag.ar(LFPulse.ar(freq / div, 0, width) * amp, lagTime)
      val flt     = in * pulse
      mix(in, flt, pMix)
    }

    // -------------- SINKS --------------
    val recFormat = new SimpleDateFormat("'rec_'yyMMdd'_'HHmmss'.aif'", Locale.US)

    val sinkRecPrepare = new Action.Body {
      def apply[T <: evt.Sys[T]](universe: Universe[T])(implicit tx: T#Tx): Unit = {
        import universe._
        for {
          art  <- self.attr[ArtifactElem]("file")
          artM <- art.modifiableOption
        } {
          val name  = recFormat.format(new Date)
          artM.child = Artifact.Child(name) // XXX TODO - should check that it is different from previous value
          // println(name)
        }
      }
    }
    Action.registerPredef("nuages-prepare-rec", sinkRecPrepare)

    val sinkRecDispose = new Action.Body {
      def apply[T <: evt.Sys[T]](universe: Universe[T])(implicit tx: T#Tx): Unit = {
        import universe._
        for {
          art <- self.attr[ArtifactElem]("file")
        } {
          val f = art.value
          SoundProcesses.scheduledExecutorService.schedule(new Runnable {
            def run(): Unit = SoundProcesses.atomic[S, Unit] { implicit tx =>
              println(f)
              mkLoop(f)
            }
          }, 1000, TimeUnit.MILLISECONDS)
        }
      }
    }
    Action.registerPredef("nuages-dispose-rec", sinkRecDispose)

    val sinkRec = sink("rec") { in =>
      // val pStop = pControl("stop", ParamSpec(0, 1, step = 1), default = 0)
      proc.graph.DiskOut.ar("file", in)
      // Mix.mono(in).poll(1, "poll")
    }
    val sinkPrepObj = Obj(Action.Elem(Action.predef("nuages-prepare-rec")))
    val sinkDispObj = Obj(Action.Elem(Action.predef("nuages-dispose-rec")))
    val artRec      = loc.add(loc.directory / "undefined")
    val artRecObj   = Obj(ArtifactElem(artRec))
    sinkPrepObj.attr.put("file"   , artRecObj  )
    sinkDispObj.attr.put("file"   , artRecObj  )
    sinkRec    .attr.put("file"   , artRecObj  )
    sinkRec    .attr.put("nuages-prepare", sinkPrepObj)
    sinkRec    .attr.put("nuages-dispose", sinkDispObj)

    //    prepare(sinkRec) { implicit tx =>
    //      obj => ...
    //    }

    // -------------- DIFFUSIONS --------------

    masterChansOption.foreach { masterChans =>
      val numChans          = masterChans.size
      val masterCfg         = NamedBusConfig("", 0, numChans)
      val masterGroupsCfg   = masterCfg +: sConfig.masterGroups

      masterGroupsCfg.zipWithIndex.foreach { case (cfg, idx) =>
        def placeChannels(sig: GE): GE = {
          if (cfg.numChannels == numChans) sig
          else {
            Seq(Silent.ar(cfg.offset),
              Flatten(sig),
              Silent.ar(numChans - (cfg.offset + cfg.numChannels))): GE
          }
        }

        def mkAmp(): GE = {
          val db0 = pAudio("amp", ParamSpec(-inf, 20, DbFaderWarp), -inf)
          val db  = db0 - 10 * (db0 < -764)  // FUCKING BUG IN SUPERCOLLIDER. HELL WHY ARE PEOPLE WRITING C CODE. SHIT LANGUAGE
          val res = db.dbamp
          CheckBadValues.ar(res, id = 666)
          res
        }

        def mkOutAll(in: GE): GE = {
          val pAmp          = mkAmp()
          val sig           = in * Lag.ar(pAmp, 0.1) // .outputs
          val outChannels   = cfg.numChannels
          val outSig        = WrapExtendChannels(outChannels, sig)
          placeChannels(outSig)
        }

        def mkOutPan(in: GE): GE = {
          val pSpread       = pControl("spr" , ParamSpec(0.0, 1.0),   default = 0.25) // XXX rand
          val pRota         = pControl("rota", ParamSpec(0.0, 1.0),   default = 0.0)
          val pBase         = pControl("azi" , ParamSpec(0.0, 360.0), default = 0.0)
          val pAmp          = mkAmp()

          val baseAzi       = Lag.kr(pBase, 0.5) + IRand(0, 360)
          val rotaAmt       = Lag.kr(pRota, 0.1)
          val spread        = Lag.kr(pSpread, 0.5)
          val outChannels   = cfg.numChannels
          val rotaSpeed     = 0.1
          val inSig         = in * Lag.ar(pAmp, 0.1) // .outputs
          val noise         = LFDNoise1.kr(rotaSpeed) * rotaAmt * 2
          val pos0          = ChannelIndices(in) * 2 / NumChannels(in)
          // pos0.poll(0, "pos0")
          val pos1          = (baseAzi / 180) + pos0
          val pos           = pos1 + noise
          val level         = 1
          val width         = (spread * (outChannels - 2)) + 2
          val panAz         = PanAz.ar(outChannels, inSig, pos, level, width, 0)
          // tricky
          val outSig        = Mix(panAz)
          placeChannels(outSig)
        }

        def mkOutRnd(in: GE): GE = {
          val pAmp        = mkAmp()
          val pFreq       = pControl("freq", ParamSpec(0.01, 10, ExpWarp), default = 0.1)
          val pPow        = pControl("pow" , ParamSpec(1, 10), default = 2)
          val pLag        = pControl("lag" , ParamSpec(0.1, 10), default = 1)

          val sig         = in * Lag.ar(pAmp, 0.1) // .outputs
          val outChannels = cfg.numChannels
          val sig1        = WrapExtendChannels(outChannels, sig)
          val freq        = pFreq
          val lag         = pLag
          val pw          = pPow
          val rands       = Lag.ar(TRand.ar(0, 1, Dust.ar(List.fill(outChannels)(freq))).pow(pw), lag)
          val outSig      = sig1 * rands
          placeChannels(outSig)
        }

        if (nConfig.collector) {
          filter(s"O-all${cfg.name}")(mkOutAll)
          filter(s"O-pan${cfg.name}")(mkOutPan)
          filter(s"O-rnd${cfg.name}")(mkOutRnd)
        } else {
          def mkDirectOut(sig0: GE): Unit = {
            val bad = CheckBadValues.ar(sig0)
            val sig = Gate.ar(sig0, bad sig_== 0)
            masterChans.zipWithIndex.foreach { case (ch, i) =>
              val sig0 = sig \ i
              val hpf  = sConfig.highPass
              val sig1 = if (hpf >= 16 && hpf < 20000) HPF.ar(sig0, hpf) else sig0
              Out.ar(ch, sig1)   // XXX TODO - should go to a bus w/ limiter
            }
          }

          collector(s"O-all${cfg.name}") { in =>
            val sig = mkOutAll(in)
            mkDirectOut(sig)
          }

          collector(s"O-pan${cfg.name}") { in =>
            val sig = mkOutPan(in)
            mkDirectOut(sig)
          }

          collector(s"O-rnd${cfg.name}") { in =>
            val sig = mkOutRnd(in)
            mkDirectOut(sig)
          }
        }
      }
    }

    collector("O-mute") { in => DC.ar(0) }
  }
}