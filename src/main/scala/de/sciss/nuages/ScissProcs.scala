/*
 *  ScissProcs.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2014 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.file._
import de.sciss.lucre.artifact.ArtifactLocation
import de.sciss.lucre.synth.Sys
import de.sciss.nuages.Wolkenpumpe.DSL
import de.sciss.synth
import de.sciss.synth.io.AudioFile
import de.sciss.synth.proc.{AuralSystem, AudioGraphemeElem, ExprImplicits, Grapheme, Obj}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.language.implicitConversions

/** This is my personal set of generators and filters. */
object ScissProcs {
  trait ConfigLike {
    def numLoops: Int

    /** Maximum duration in seconds. */
    def loopDuration: Double

    def audioFilesFolder: Option[File]

    def lineInputs  : Vec[NamedBusConfig]
    def micInputs   : Vec[NamedBusConfig]
    def lineOutputs : Vec[NamedBusConfig]
    def masterGroups: Vec[NamedBusConfig]
  }

  object Config {
    implicit def build(b: ConfigBuilder): Config = b.build

    def apply(): ConfigBuilder = new ConfigBuilder
  }

  sealed trait Config extends ConfigLike

  final class ConfigBuilder private[ScissProcs]() extends ConfigLike {
    private var numLoopsVar: Int = 7

    def numLoops: Int = numLoopsVar

    def numLoops_=(n: Int): Unit = {
      require(n >= 0)
      numLoopsVar = n
    }

    var loopDuration: Double = 30.0

    var audioFilesFolder: Option[File] = None

    var lineInputs  : Vec[NamedBusConfig] = Vec.empty
    var micInputs   : Vec[NamedBusConfig] = Vec.empty
    var lineOutputs : Vec[NamedBusConfig] = Vec.empty
    var masterGroups: Vec[NamedBusConfig] = Vec.empty

    def build: Config = ConfigImpl(numLoops = numLoops, loopDuration = loopDuration,
      audioFilesFolder = audioFilesFolder, lineInputs = lineInputs, micInputs = micInputs,
      lineOutputs = lineOutputs, masterGroups = masterGroups)
  }

  private case class ConfigImpl(numLoops: Int, loopDuration: Double,
                                audioFilesFolder: Option[File],
                                lineInputs : Vec[NamedBusConfig], micInputs   : Vec[NamedBusConfig],
                                lineOutputs: Vec[NamedBusConfig], masterGroups: Vec[NamedBusConfig])
    extends Config

  var tapePath: Option[String] = None

  def apply[S <: Sys[S]](config: Config)(implicit tx: S#Tx, nuages: Nuages[S], aural: AuralSystem): Unit = {
    import synth._
    import ugen._

    val dsl = new DSL[S]
    import dsl._
    val imp = ExprImplicits[S]
    import imp._

    // val masterBusOption = settings.frame.panel.masterBus

    // -------------- GENERATORS --------------

    generator("tape") {
      val pSpeed    = pAudio  ("speed", ParamSpec(0.1, 10, ExpWarp), default = 1)
      val pLoop     = pControl("loop" , ParamSpec(0, 1, LinWarp, 1), default = 0)

      //      val pPos      = pScalar("pos"  , ParamSpec(0, 1), default = 0)

      //      val path      = tapePath.getOrElse(sys.error("No audio-file selected"))
      //      val spec      = audioFileSpec(path)
      //      val numFrames = spec.numFrames
      //      val startPos  = pPos.v
      //      val startFr   = ((if (startPos < 1) startPos else 0.0) * numFrames).toLong
      //      val b         = bufCue(path, startFrame = startFr)
      //      val numCh     = b.numChannels
      val speed     = A2K.kr(pSpeed) // * BufRateScale.ir(b.id)

      val disk      = proc.graph.VDiskIn.ar("tape", speed = speed, loop = pLoop, maxSpeed = 10)

      //      val lp0       = pLoop.v
      //
      //      // pos feedback
      //      val framesRead = Integrator.kr(speed) * (SampleRate.ir / ControlRate.ir)

      //      val me = Proc.local
      //      Impulse.kr( 10 ).react( framesRead ) { smp => ProcTxn.spawnAtomic { implicit tx =>
      //        val frame  = startFr + smp( 0 )
      //        // not sure we can access them in this scope, so just retrieve the controls...
      //        val ppos   = me.control( "pos" )
      //        //               val ploop  = me.control( "loop" )
      //        if( lp0 == 1 ) {
      //          ppos.v = (frame % numFrames).toDouble / numFrames
      //        } else {
      //          val pos = (frame.toDouble / numFrames).min( 1.0 )
      //          ppos.v = pos
      //          if( pos == 1.0 ) me.stop
      //        }
      //      }}

      // val disk = VDiskIn.ar(numCh, b.id, speed, loop = pLoop)
      disk // WrapExtendChannels( 2, disk )
    }

    config.audioFilesFolder.foreach { folder =>
      val loc = ArtifactLocation[S](folder)

      val audioFiles = folder.children
      audioFiles.filter(AudioFile.identify(_).isDefined).foreach(f => {
        val name = f.base

        val procObj = generator(name) {
          val p1    = pAudio("speed", ParamSpec(0.1f, 10f, ExpWarp), 1)
          val speed = p1
          val disk  = proc.graph.VDiskIn.ar("file", speed = speed, loop = 1)
          //          val b     = bufCue(path)
          //          val disk  = VDiskIn.ar(b.numChannels, b.id, p1.ar * BufRateScale.ir(b.id), loop = 1)
          // HPF.ar( disk, 30 )
          disk
        }

        val art   = loc.add(f)
        val spec  = AudioFile.readSpec(f)
        val gr    = Grapheme.Expr.Audio(art, spec, 0L, 1.0)
        procObj.attr.put("file", Obj(AudioGraphemeElem(gr)))
      })
    }

    //    masterBusOption.foreach { masterBus =>
    //      generator( "(test)" ) {
    //        val pAmp    = pControl( "amp",  ParamSpec( 0.01, 1.0, ExpWarp ), 1 )
    //        val pSig    = pControl( "sig",  ParamSpec( 0, 1, LinWarp, 1 ), 0 )
    //        val pFreq   = pControl( "freq", ParamSpec( 0.1, 10, ExpWarp ), 1 )
    //
    //        val idx = Stepper.kr( Impulse.kr( pFreq ), lo = 0, hi = masterBus.numChannels )
    //        val sig0: GE = Seq( WhiteNoise.ar( 1 ), SinOsc.ar( 441 ))
    //        val sig = Select.ar( pSig, sig0 ) * pAmp
    //        val sigOut: GE = Seq.tabulate( masterBus.numChannels )( ch => sig * (1 - (ch - idx).abs.min( 1 )))
    //        sigOut
    //      }
    //    }

    val loopFrames  = (config.loopDuration * 44100 /* config.server.sampleRate */).toInt
    //    val loopBuffers = Vec.fill[Buffer](config.numLoops)(Buffer.alloc(config.server, loopFrames, 2))
    //    val loopBufIDs  = loopBuffers.map(_.id)

    //    if (settings.numLoops > 0) {
    //      generator("loop") {
    //        val pBuf        = pControl("buf"  , ParamSpec(0, config.numLoops - 1, LinWarp, 1), default = 0)
    //        val pSpeed      = pAudio  ("speed", ParamSpec(0.125, 2.3511, ExpWarp), default = 1)
    //        val pStart      = pControl("start", ParamSpec(0, 1), default = 0)
    //        val pDur        = pControl("dur"  , ParamSpec(0, 1), default = 1)
    //
    //        val trig1       = LocalIn.kr(1)
    //        val gateTrig1   = PulseDivider.kr(trig = trig1, div = 2, start = 1)
    //        val gateTrig2   = PulseDivider.kr(trig = trig1, div = 2, start = 0)
    //        val startFrame  = pStart * loopFrames
    //        val numFrames   = pDur * (loopFrames - startFrame)
    //        val lOffset     = Latch.kr(in = startFrame, trig = trig1)
    //        val lLength     = Latch.kr(in = numFrames, trig = trig1)
    //        val speed       = A2K.kr(pSpeed)
    //        val duration    = lLength / (speed * SampleRate.ir) - 2
    //        val gate1       = Trig1.kr(in = gateTrig1, dur = duration)
    //        val env         = Env.asr(2, 1, 2, Curve.lin) // \sin
    //        val bufID       = Select.kr(pBuf, loopBufIDs)
    //        val play1       = PlayBuf.ar(2, bufID, speed, gateTrig1, lOffset, loop = 0)
    //        val play2       = PlayBuf.ar(2, bufID, speed, gateTrig2, lOffset, loop = 0)
    //        val amp0        = EnvGen.kr(env, gate1) // 0.999 = bug fix !!!
    //        val amp2        = 1.0 - amp0.squared
    //        val amp1        = 1.0 - (1.0 - amp0).squared
    //        val sig         = (play1 * amp1) + (play2 * amp2)
    //        LocalOut.kr(Impulse.kr(1.0 / duration.max(0.1)))
    //        sig
    //      }
    //    }

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

    config.micInputs.foreach { cfg =>
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

        val numOut = 2 // XXX configurable
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

    config.lineInputs.foreach { cfg =>
      generator(cfg.name) {
        val pBoost = pAudio("gain", ParamSpec(0.1, 10, ExpWarp), default = 1)

        val boost   = pBoost
        val sig     = In.ar(NumOutputBuses.ir + cfg.offset, cfg.numChannels) * boost
        val numOut  = 2 // XXX configurable
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

      val freq = pFreq
      Decay.ar(Dust.ar(freq :: freq :: Nil), pDecay)
    }

    generator("~gray") {
      val pAmp = pAudio("amp", ParamSpec(0.01, 1, ExpWarp), default = 0.1)
      GrayNoise.ar(1 :: 1 :: Nil) * pAmp
    }

    generator("~sin") {
      val pFreq1  = pAudio("freq"     , ParamSpec(0.1 , 10000, ExpWarp), default = 15 /* 1 */)
      val pFreq2  = pAudio("freq-fact", ParamSpec(0.01,   100, ExpWarp), default =  1 /* 1 */)
      val pAmp    = pAudio("amp"      , ParamSpec(0.01,     1, ExpWarp), default =  0.1)

      val f1 = pFreq1
      val f2 = f1 * pFreq2
      SinOsc.ar(f1 :: f2 :: Nil) * pAmp
    }

    generator("~pulse") {
      val pFreq1  = pAudio("freq"     , ParamSpec(0.1 , 10000, ExpWarp), default = 15 /* 1 */)
      val pFreq2  = pAudio("freq-fact", ParamSpec(0.01,   100, ExpWarp), default =  1 /* 1 */)
      val pw1     = pAudio("width1"   , ParamSpec(0.0 ,     1.0),        default =  0.5)
      val pw2     = pAudio("width2"   , ParamSpec(0.0 ,     1.0),        default =  0.5)
      val pAmp    = pAudio("amp"      , ParamSpec(0.01,     1, ExpWarp), default =  0.1)

      val f1 = pFreq1
      val f2 = f1 * pFreq2
      val w1 = pw1
      val w2 = pw2
      Pulse.ar(f1 :: f2 :: Nil, w1 :: w2 :: Nil) * pAmp
    }

    // -------------- FILTERS --------------

    def mix(in: GE, flt: GE, mix: GE): GE = LinXFade2.ar(in, flt, mix * 2 - 1)
    def mkMix(): GE = pAudio("mix", ParamSpec(0, 1), default = 0)

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
      val pBits = pAudio("bits", ParamSpec(2, 14, LinWarp, 1), default = 14)
      val pMix  = mkMix()

      val flt = MantissaMask.ar(in, pBits)
      mix(in, flt, pMix)
    }

    filter("achil") { in =>
      val pSpeed  = pAudio("speed", ParamSpec(0.125, 2.3511, ExpWarp), default = 0.5)
      val pMix    = mkMix()

      val speed       = Lag.ar(pSpeed, 0.1)
      val numFrames   = SampleRate.ir // sampleRate.toInt
      val bufID       = LocalBuf(numFrames = numFrames, numChannels = Pad(1, in))
      val writeRate   = BufRateScale.kr(bufID)
      val readRate    = writeRate * speed
      val readPhasor  = Phasor.ar(0, readRate, 0, numFrames)
      val read        = BufRd.ar(1, bufID, readPhasor, 0, 4)
      val writePhasor = Phasor.ar(0, writeRate, 0, numFrames)
      val old         = BufRd.ar(1, bufID, writePhasor, 0, 1)
      val wet0        = SinOsc.ar(0, (readPhasor - writePhasor).abs / numFrames * math.Pi)
      val dry         = 1 - wet0.squared
      val wet         = 1 - (1 - wet0).squared
      val writeSig    = (old * dry) + (in * wet)
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
      val pFreq = pAudio("freq", ParamSpec(-1, 1), 0.0)
      val pMix  = mkMix()

      val freq    = pFreq
      val freqHz  = freq.abs.linexp(0, 1, 20, 12000) * freq.signum
      val flt     = FreqShift.ar(in, freqHz)
      mix(in, flt, pMix)
    }

    filter("reso") { in =>
      val pFreq   = pAudio("freq", ParamSpec(30, 13000, ExpWarp), default = 400) // beware of the upper frequency
      val pFreq2  = pAudio("freq-fact", ParamSpec(0.5, 2, ExpWarp), default = 1)
      val pq      = pAudio("q", ParamSpec(0.5, 50, ExpWarp), default = 1)
      val pMix    = mkMix()

      val freq0   = pFreq
      val freq    = freq0 :: (freq0 * pFreq2).max(30).min(13000) :: Nil
      val rq      = pq.reciprocal
      val makeUp  = (rq + 0.5).pow(1.41) // rq.max( 1 ) // .sqrt
      val flt     = Resonz.ar(in, freq, rq) * makeUp
      mix(in, flt, pMix)
    }

    filter("notch") { in =>
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
      val run         = recLevel > 0
      RecordBuf.ar(Pad.Split(in), buf = buf, offset = off, recLevel = recLevel, preLevel = preLevel, run = run, loop = 1)
      LocalOut.kr(Impulse.kr(1.0 / (dur + fadeIn + fadeOut).max(0.01)))

      val speed       = pSpeed
      val play0       = PlayBuf.ar(1 /* numChannels */, buf, speed, loop = 1)
      val play        = Flatten(play0)
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

    //    filter("~onsets") { in =>
    //      val pThresh = pControl("thresh", ParamSpec(0, 1), default = 0.5)
    //      val pDecay  = pAudio  ("decay" , ParamSpec(0, 1), default = 0)
    //
    //      val pMix    = mkMix()
    //
    //      val numChannels = in.numChannels // numOutputs
    //      val bufIDs      = Seq.fill(numChannels)(bufEmpty(1024).id)
    //      val chain1      = FFT(bufIDs, in)
    //      val onsets      = Onsets.kr(chain1, pThresh)
    //      val sig         = Decay.ar(Trig1.ar(onsets, SampleDur.ir), pDecay).min(1) // * 2 - 1
    //      mix(in, sig, pMix)
    //    }

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

    // XXX this has problems with UDP max datagram size
    //      filter( "renoise" ) {
    //         val pcolor  = pAudio( "color", ParamSpec( 0, 1 ), 0 )
    //         val pmix    = pMix
    //         val step	   = 0.5
    //         val freqF   = math.pow( 2, step )
    //         val freqs	= Array.iterate( 32.0, 40 )( _ * freqF ).filter( _ <= 16000 )
    //         graph { in =>
    //            val color         = Lag.ar( pcolor.ar, 0.1 )
    //            val numChannels   = in.numOutputs
    //            val noise	      = WhiteNoise.ar( numChannels )
    //            val sig           = freqs.foldLeft[ GE ]( 0 ){ (sum, freq) =>
    //               val filt       = BPF.ar( in, freq, step )
    //               val freq2      = ZeroCrossing.ar( filt )
    //               val w0         = Amplitude.ar( filt )
    //               val w2         = w0 * color
    //               val w1         = w0 * (1 - color)
    //               sum + BPF.ar( (noise * w1) + (LFPulse.ar( freq2 ) * w2), freq, step )
    //            }
    //            val amp           = step.reciprocal  // compensate for Q
    //            val flt           = sig * amp
    //            mix( in, flt, pmix )
    //         }
    //      }

    // XXX TODO
    //      filter( "verb" ) {
    //         val pextent = pScalar( "size", ParamSpec( 0, 1 ), 0.5 )
    //         val pcolor  = pControl( "color", ParamSpec( 0, 1 ), 0.5 )
    //         val pmix    = pMix
    //         graph { in: In =>
    //            val extent     = pextent.ir
    //            val color	   = Lag.kr( pcolor.kr, 0.1 )
    //            val i_roomSize	= LinExp.ir( extent, 0, 1, 1, 100 )
    //            val i_revTime  = LinExp.ir( extent, 0, 1, 0.3, 20 )
    //            val spread	   = 15
    //            val numChannels= in.numOutputs
    //            val ins        = in.outputs
    //            val verbs      = (ins :+ ins.last).grouped( 2 ).toSeq.flatMap( pair =>
    //               (GVerb.ar( Mix( pair ), i_roomSize, i_revTime, color, color, spread, 0, 1, 0.7, i_roomSize ) * 0.3).outputs
    //            )
    //// !! BUG IN SCALA 2.8.0 : CLASSCASTEXCEPTION
    //// weird stuff goin on with UGenIn seqs...
    //            val flt: GE     = Vector( verbs.take( numChannels ): _* ) // drops last one if necessary
    //            mix( in, flt, pmix )
    //         }
    //      }

    filter("zero") { in =>
      val pWidth  = pAudio("width", ParamSpec(0, 1), default = 0.5)
      val pDiv    = pAudio("div"  , ParamSpec(1, 10, LinWarp, 1), default = 1)
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

    // -------------- DIFFUSIONS --------------

    //    masterBusOption.foreach { masterBus =>
    //      val masterCfg        = NamedBusConfig( "", 0, masterBus.numChannels )
    //      val masterGroupsCfg  = masterCfg +: settings.masterGroups
    //
    //      masterGroupsCfg.zipWithIndex.foreach { case (cfg, idx) =>
    //        def placeChannels( sig: GE ) : GE = {
    //          if( cfg.numChannels == masterBus.numChannels ) sig else {
    //            //               IndexedSeq.fill( chanOff )( Constant( 0 )) ++ sig.outputs ++ IndexedSeq.fill( masterBus.numChannels - (numCh + chanOff) )( Constant( 0 ))
    //            Seq( Silent.ar( cfg.offset ),
    //              Flatten( sig ),
    //              Silent.ar( masterBus.numChannels - (cfg.offset + cfg.numChannels) )) : GE
    //          }
    //        }
    //
    //        if( settings.frame.config.collector ) {
    //          filter( "O-all" + cfg.name ) {
    //            val pamp  = pAudio( "amp", ParamSpec( 0.01, 10, ExpWarp ), 1 )
    //
    //            graph { in: In =>
    //              val sig           = (in * Lag.ar( pamp.ar, 0.1 )) // .outputs
    //            val outChannels   = cfg.numChannels
    //              val outSig        = WrapExtendChannels( outChannels, sig )
    //              placeChannels( outSig )
    //            }
    //          }
    //
    //          filter( "O-pan" + cfg.name ) {
    //            val pspread = pControl( "spr",  ParamSpec( 0.0, 1.0 ), 0.25 ) // XXX rand
    //            val prota   = pControl( "rota", ParamSpec( 0.0, 1.0 ), 0.0 )
    //            val pbase   = pControl( "azi",  ParamSpec( 0.0, 360.0 ), 0.0 )
    //            val pamp    = pAudio( "amp", ParamSpec( 0.01, 10, ExpWarp ), 1 )
    //
    //            graph { in: In =>
    //              val baseAzi       = Lag.kr( pbase.kr, 0.5 ) + IRand( 0, 360 )
    //              val rotaAmt       = Lag.kr( prota.kr, 0.1 )
    //              val spread        = Lag.kr( pspread.kr, 0.5 )
    //              val inChannels    = in.numChannels // numOutputs
    //            val outChannels   = cfg.numChannels
    //              val rotaSpeed     = 0.1
    //              val inSig         = (in * Lag.ar( pamp.ar, 0.1 )) // .outputs
    //            val noise         = LFDNoise1.kr( rotaSpeed ) * rotaAmt * 2
    //              val altern        = false
    //              val pos: GE       = Seq.tabulate( inChannels ) { inCh =>
    //                val pos0 = if( altern ) {
    //                  (baseAzi / 180) + (inCh / outChannels * 2)
    //                } else {
    //                  (baseAzi / 180) + (inCh / inChannels * 2)
    //                }
    //                pos0 + noise
    //              }
    //              val level         = 1
    //              val width         = (spread * (outChannels - 2)) + 2
    //              //println( "PanAz : " + outChannels )
    //              // XXX tricky Mix motherfucker -- is that sound processes (?) somewhere checks the
    //              // num channels in a wrong way.
    //              val outSig        = Mix( PanAz.ar( outChannels, inSig, pos, level, width, 0 ))
    //              placeChannels( outSig )
    //            }
    //          }
    //
    //          filter( "O-rnd" + cfg.name ) {
    //            val pamp  = pAudio( "amp", ParamSpec( 0.01, 10, ExpWarp ), 1 )
    //            val pfreq = pControl( "freq", ParamSpec( 0.01, 10, ExpWarp ), 0.1 )
    //            val ppow  = pControl( "pow", ParamSpec( 1, 10 ), 2 )
    //            val plag  = pControl( "lag", ParamSpec( 0.1, 10 ), 1 )
    //
    //            graph { in: In =>
    //              val sig          = (in * Lag.ar( pamp.ar, 0.1 )) // .outputs
    //            //                  val inChannels   = in.numChannels // sig.size
    //            val outChannels  = cfg.numChannels
    //              val sig1         = WrapExtendChannels( outChannels, sig )
    //              val freq         = pfreq.kr
    //              val lag          = plag.kr
    //              val pw           = ppow.kr
    //              val rands        = Lag.ar( TRand.ar( 0, 1, Dust.ar( List.fill( outChannels )( freq ))).pow( pw ), lag )
    //              val outSig       = sig1 * rands
    //              placeChannels( outSig )
    //            }
    //          }
    //
    //        } else {
    //          diff( "O-all" + cfg.name ) {
    //            val pamp  = pAudio( "amp", ParamSpec( 0.01, 10, ExpWarp ), 1 )
    //            val pout  = pAudioOut( "out", None )
    //
    //            graph { in: In =>
    //              val sig          = (in * Lag.ar( pamp.ar, 0.1 )) // .outputs
    //            //                  val inChannels   = in.numChannels // sig.size
    //            val outChannels  = cfg.numChannels
    //              val outSig        = WrapExtendChannels( outChannels, sig )
    //              pout.ar( placeChannels( outSig ))
    //            }
    //          }
    //
    //          diff( "O-pan" + cfg.name ) {
    //            val pspread = pControl( "spr",  ParamSpec( 0.0, 1.0 ), 0.25 ) // XXX rand
    //            val prota   = pControl( "rota", ParamSpec( 0.0, 1.0 ), 0.0 )
    //            val pbase   = pControl( "azi",  ParamSpec( 0.0, 360.0 ), 0.0 )
    //            val pamp    = pAudio( "amp", ParamSpec( 0.01, 10, ExpWarp ), 1 )
    //            val pout    = pAudioOut( "out", None )
    //
    //            graph { in: In =>
    //              val baseAzi       = Lag.kr( pbase.kr, 0.5 ) + IRand( 0, 360 )
    //              val rotaAmt       = Lag.kr( prota.kr, 0.1 )
    //              val spread        = Lag.kr( pspread.kr, 0.5 )
    //              val inChannels   = in.numChannels // numOutputs
    //            val outChannels  = cfg.numChannels
    //              val rotaSpeed     = 0.1
    //              val inSig         = (in * Lag.ar( pamp.ar, 0.1 )) // .outputs
    //            val noise         = LFDNoise1.kr( rotaSpeed ) * rotaAmt * 2
    //              val altern        = false
    //              val pos: GE       = Seq.tabulate( inChannels ) { inCh =>
    //                val pos0 = if( altern ) {
    //                  (baseAzi / 180) + (inCh / outChannels * 2)
    //                } else {
    //                  (baseAzi / 180) + (inCh / inChannels * 2)
    //                }
    //                pos0 + noise
    //              }
    //              val level         = 1
    //              val width         = (spread * (outChannels - 2)) + 2
    //              val outSig        = PanAz.ar( outChannels, inSig, pos, level, width, 0 )
    //              pout.ar( placeChannels( outSig ))
    //            }
    //          }
    //
    //          diff( "O-rnd" + cfg.name ) {
    //            val pamp  = pAudio( "amp", ParamSpec( 0.01, 10, ExpWarp ), 1 )
    //            val pfreq = pControl( "freq", ParamSpec( 0.01, 10, ExpWarp ), 0.1 )
    //            val ppow  = pControl( "pow", ParamSpec( 1, 10 ), 2 )
    //            val plag  = pControl( "lag", ParamSpec( 0.1, 10 ), 1 )
    //            val pout  = pAudioOut( "out", None )
    //
    //            graph { in: In =>
    //              val sig          = (in * Lag.ar( pamp.ar, 0.1 )) // .outputs
    //            //                  val inChannels   = in.numChannels // sig.size
    //            val outChannels  = cfg.numChannels
    //              val sig1          = WrapExtendChannels( outChannels, sig )
    //              val freq         = pfreq.kr
    //              val lag          = plag.kr
    //              val pw           = ppow.kr
    //              val rands        = Lag.ar( TRand.ar( 0, 1, Dust.ar( List.fill( outChannels )( freq ))).pow( pw ), lag )
    //              val outSig       = sig1 * rands
    //              pout.ar( placeChannels( outSig ))
    //            }
    //          }
    //        }
    //      }
    //    }

    collector( "O-mute" ) { in =>
      DC.ar(0)
    }

    //    val dfPostM = SynthDef( "post-master" ) {
    //      val masterBus = settings.frame.panel.masterBus.get // XXX ouch
    //      val sigMast = In.ar( masterBus.index, masterBus.numChannels )
    //      // externe recorder
    //      settings.lineOutputs.foreach { cfg =>
    //        val off     = cfg.offset
    //        val numOut  = cfg.numChannels
    //        val numIn   = masterBus.numChannels
    //        val sig1: GE = if( numOut == numIn ) {
    //          sigMast
    //        } else if( numIn == 1 ) {
    //          Seq.fill[ GE ]( numOut )( sigMast )
    //        } else {
    //          val sigOut = SplayAz.ar( numOut, sigMast )
    //          Limiter.ar( sigOut, (-0.2).dbamp )
    //        }
    //        //            assert( sig1.numOutputs == numOut )
    //        Out.ar( off, sig1 )
    //      }
    //      // master + people meters
    //      if( settings.controlPanel.isDefined ) {
    //        val meterTr    = Impulse.kr( 20 )
    //        val (peoplePeak, peopleRMS) = {
    //          val groups = if( NuagesApp.METER_MICS ) settings.micInputs ++ settings.lineInputs else settings.lineInputs
    //          val res = groups.map { cfg =>
    //            val off        = cfg.offset
    //            val numIn      = cfg.numChannels
    //            val pSig       = In.ar( NumOutputBuses.ir + off, numIn )
    //            val peak       = Peak.kr( pSig, meterTr ) // .outputs
    //          val peakM      = Reduce.max( peak )
    //            val rms        = A2K.kr( Lag.ar( pSig.squared, 0.1 ))
    //            val rmsM       = Mix.mono( rms ) / numIn
    //            (peakM, rmsM)
    //          }
    //          (res.map( _._1 ): GE) -> (res.map( _._2 ): GE)  // elegant it's not
    //        }
    //        val masterPeak    = Peak.kr( sigMast, meterTr )
    //        val masterRMS     = A2K.kr( Lag.ar( sigMast.squared, 0.1 ))
    //        val peak: GE      = Flatten( Seq( masterPeak, peoplePeak ))
    //        val rms: GE       = Flatten( Seq( masterRMS, peopleRMS ))
    //        val meterData     = Zip( peak, rms )  // XXX correct?
    //        SendReply.kr( meterTr, meterData, "/meters" )
    //      }
    //    }
    //    val synPostM = dfPostM.play( settings.server, addAction = addToTail )
    //    settings.controlPanel.foreach { ctrl =>
    //      val synPostMID = synPostM.id
    //      osc.Responder.add( settings.server ) {
    //        case Message( "/meters", `synPostMID`, 0, values @ _* ) =>
    //          EventQueue.invokeLater( new Runnable { def run() {
    //            ctrl.meterUpdate( values.map( _.asInstanceOf[ Float ])( breakOut ))
    //          }})
    //      }
    //    }
  }
}