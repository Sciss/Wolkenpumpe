package de.sciss.nuages

import de.sciss.file._
import de.sciss.lucre.stm
import de.sciss.lucre.stm.store.BerkeleyDB
import de.sciss.lucre.synth.{InMemory, Sys}
import de.sciss.submin.Submin
import de.sciss.synth.Server
import de.sciss.synth.proc.Durable
import org.rogach.scallop.{ScallopConf, ScallopOption => Opt}

object Demo {

  case class Config(durable: Option[File] = None, timeline: Boolean = false, dumpOSC: Boolean = false,
                    recDir: Option[File] = None, log: Boolean = false)

  def main(args: Array[String]): Unit = {
    object p extends ScallopConf(args) {
      printedName = "Demo"

      val durable : Opt[File]     = opt(descr = "Durable database")
      val timeline: Opt[Boolean]  = opt(descr = "Use performance timeline")
      val dumpOSC : Opt[Boolean]  = opt(name = "dump-osc", descr = "Dump OSC messages")
      val log     : Opt[Boolean]  = opt(descr = "Enable logging")
      val recDir  : Opt[File]     = opt(name = "rec-dir", descr = "Snippet audio recordings directory")

      verify()
      val config = Config(durable = durable.toOption, timeline = timeline(), dumpOSC = dumpOSC(), log = log(),
        recDir = recDir.toOption)
    }

    run(p.config)
  }

  def mkTestProcs[S <: Sys[S]]()(implicit tx: S#Tx, nuages: Nuages[S]): Unit = {
    val dsl = DSL[S]
    import de.sciss.synth._
    import de.sciss.synth.ugen._
    import dsl._

    generator("Sprink") {
      val freq = pAudio("freq", ParamSpec(0.2, 50), 1.0)
      BPZ2.ar(WhiteNoise.ar(LFPulse.ar(freq, 0, 0.25) * Seq(0.1, 0.1)))
    }

    filter("Filt") { in =>
      val normFreq  = pAudio("freq", ParamSpec(-1, 1), 0.7)
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
      val mix       = pAudio("mix", ParamSpec(0, 1), 0.0 /* 1 */)
      LinXFade2.ar(in, flt, mix * 2 - 1)
    }

    filter("Achil") { in =>
      val speed         = Lag.ar(pAudio("speed", ParamSpec(0.125, 2.3511, ExponentialWarp), 0.5), 0.1)
      val numFrames     = 44100 // sampleRate.toInt
    val numChannels   = 2     // in.numChannels // numOutputs
      //println( "numChannels = " + numChannels )

      // val buf           = bufEmpty(numFrames, numChannels)
      // val bufID         = buf.id
      val bufID         = LocalBuf(numFrames = numFrames, numChannels = numChannels)

      val writeRate     = BufRateScale.kr(bufID)
      val readRate      = writeRate * speed
      val readPhasor    = Phasor.ar(0, readRate, 0, numFrames)
      val read          = BufRd.ar(numChannels, bufID, readPhasor, 0, 4)
      val writePhasor   = Phasor.ar(0, writeRate, 0, numFrames)
      val old           = BufRd.ar(numChannels, bufID, writePhasor, 0, 1)
      val wet0          = SinOsc.ar(0, (readPhasor - writePhasor).abs / numFrames * math.Pi)
      val dry           = 1 - wet0.squared
      val wet           = 1 - (1 - wet0).squared
      BufWr.ar((old * dry) + (in * wet), bufID, writePhasor)

      val mix           = pAudio("mix", ParamSpec(0, 1), 1.0)

      LinXFade2.ar(in, read, mix * 2 - 1)
    }

    collector("Out") { in =>
      val amp = pAudio("amp", ParamSpec(-inf, 20, DbFaderWarp), Double.NegativeInfinity).dbAmp
      val sig = in * amp
      Out.ar(0, sig)
    }
  }

  val DEBUG = true

  private class DemoNuages[S <: Sys[S]](config: Config) extends WolkenpumpeMain[S] {
    /** Subclasses may want to override this. */
    override protected def configure(sCfg: ScissProcs.ConfigBuilder, nCfg: Nuages.ConfigBuilder,
                                     aCfg: Server.ConfigBuilder): Unit = {
      config.recDir.foreach { d =>
        sCfg.recDir = d
      }

      if (DEBUG) {
        nCfg.mainChannels     = Some(0 to 1)
        nCfg.soloChannels       = None
        nCfg.recordPath         = Some("/tmp")

        nCfg.micInputs          = Vector.empty
        nCfg.lineInputs         = Vector.empty
        nCfg.lineOutputs        = Vector.empty

        sCfg.genNumChannels  = 2

        aCfg.wireBuffers        = 512
        aCfg.audioBuffers       = 4096
        aCfg.blockSize          = 128
      }

      super.configure(sCfg, nCfg, aCfg)
    }
  }

  private def mkNuages[S <: Sys[S]](config: Config, nuagesH: stm.Source[S#Tx, Nuages[S]])
                                   (implicit cursor: stm.Cursor[S]): Unit = {
    val w = new DemoNuages[S](config)
    w.run(nuagesH)
    if (config.dumpOSC) cursor.step { implicit tx =>
      w.auralSystem.whenStarted(_.peer.dumpOSC(filter = m =>
        m.name != "/meters" && m.name != "/tr" && m.name != "/snap"
      ))
    }
  }

  def run(config: Config): Unit = {
    Submin.install(true)
    Wolkenpumpe.init()
    if (config.log) showLog = true

    config.durable match {
      case Some(f) =>
        type S = Durable
        val factory = if (f.path == "<TMP>") {
          BerkeleyDB.tmp()
        } else {
          BerkeleyDB.factory(f)
        }
        implicit val system: S = Durable(factory)
        val nuagesH = system.root { implicit tx =>
          if (config.timeline) Nuages.timeline[S] else Nuages.folder[S]
        }
        mkNuages(config, nuagesH)

    case None =>
      type S = InMemory
      implicit val system: S = InMemory()

      val nuagesH = system.step { implicit tx =>
        val nuages = if (config.timeline) Nuages.timeline[S] else Nuages.folder[S]
        tx.newHandle(nuages)
      }
      mkNuages(config, nuagesH)
    }
  }
}
