/*
 *  Wolkenpumpe.scala
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

import java.awt.Font

import de.sciss.file._
import de.sciss.lucre.stm
import de.sciss.lucre.synth.{InMemory, Sys}
import de.sciss.osc
import de.sciss.synth.proc.AuralSystem
import de.sciss.synth.{Server => SServer}

object Wolkenpumpe {
  def main(args: Array[String]): Unit = {
    type S = InMemory
    implicit val system = InMemory()
    val w = new Wolkenpumpe[S]
    w.run()
  }

  def mkTestProcs[S <: Sys[S]]()(implicit tx: S#Tx, nuages: Nuages[S]): Unit = {
    val dsl = new DSL[S]
    import de.sciss.synth.ugen._
    import de.sciss.synth.{Server => _, _}
    import dsl._

    generator("Sprink") {
      val freq = pAudio("freq", ParamSpec(0.2, 50), 1)
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
      val mix       = pAudio("mix", ParamSpec(0, 1), 0 /* 1 */)
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

      val mix           = pAudio("mix", ParamSpec(0, 1), 1)

      LinXFade2.ar(in, read, mix * 2 - 1)
    }

    collector("Out") { in =>
      val amp = pAudio("amp", ParamSpec(-inf, 20, DbFaderWarp), -inf).dbamp
      val sig = in * amp
      Out.ar(0, sig)
    }
  }

  private lazy val _initFont: Font = {
    val url = Wolkenpumpe.getClass.getResource("BellySansCondensed.ttf")
    // val is  = Wolkenpumpe.getClass.getResourceAsStream("BellySansCondensed.ttf")
    if (url == null) {
      new Font(Font.SANS_SERIF, Font.PLAIN, 1)
    } else {
      val is = url.openStream()
      val res = Font.createFont(Font.TRUETYPE_FONT, is)
      is.close()
      res
    }
    //      // "SF Movie Poster Condensed"
    //      new Font( "BellySansCondensed", Font.PLAIN, 12 )
  }

  private var _condensedFont: Font = _

  /** A condensed font for GUI usage. This is in 12 pt size,
    * so consumers must rescale.
    */
  def condensedFont: Font = {
    if (_condensedFont == null) _condensedFont = _initFont
    _condensedFont
  }
  def condensedFont_=(value: Font): Unit =
    _condensedFont = value
}
class Wolkenpumpe[S <: Sys[S]] {
  /** Subclasses may want to override this. */
  protected def configure(sCfg: ScissProcs.ConfigBuilder, nCfg: Nuages.ConfigBuilder,
                          aCfg: SServer.ConfigBuilder): Unit = {
    nCfg.masterChannels     = Some(0 until 5) // Vector(0, 1))
    nCfg.soloChannels       = Some(0 to 1)

    sCfg.audioFilesFolder   = Some(userHome / "Music" / "tapes")
    sCfg.micInputs          = Vector(
      NamedBusConfig("m-dpa"  , 0, 1),
      NamedBusConfig("m-at "  , 3, 1)
    )
    sCfg.lineInputs         = Vector(NamedBusConfig("pirro", 2, 1))
    sCfg.lineOutputs        = Vector(
      NamedBusConfig("sum", 5, 1)
      // , NamedBusConfig("hp", 6, 2)  // while 'solo' doesn't work
    )
  }

  /** Subclasses may want to override this. */
  protected def registerProcesses(sCfg: ScissProcs.Config, nCfg: Nuages.Config)
                                 (implicit tx: S#Tx, cursor: stm.Cursor[S], nuages: Nuages[S],
                                  aural: AuralSystem): Unit = {
    ScissProcs[S](sCfg, nCfg)
  }

  def run()(implicit cursor: stm.Cursor[S]): Unit = {
    de.sciss.nuages.initTypes()

    val nCfg                = Nuages    .Config()
    val sCfg                = ScissProcs.Config()
    val aCfg                = SServer   .Config()

    nCfg.recordPath         = Option(sys.props("java.io.tmpdir"))
    aCfg.deviceName         = Some("Wolkenpumpe")
    aCfg.audioBusChannels   = 512
    aCfg.memorySize         = 256 * 1024
    aCfg.transport          = osc.TCP
    aCfg.pickPort()

    configure(sCfg, nCfg, aCfg)

    val maxInputs   = (sCfg.lineInputs ++ sCfg.micInputs).map(_.stopOffset).max
    val maxOutputs  = math.max(
      math.max(
        sCfg.lineOutputs.map(_.stopOffset).max,
        nCfg.soloChannels  .fold(0)(_.max + 1)
      ),
      nCfg.masterChannels.fold(0)(_.max + 1)
    )

    println(s"numInputs = $maxInputs, numOutputs = $maxOutputs")

    aCfg.outputBusChannels  = maxOutputs
    aCfg.inputBusChannels   = maxInputs

    /* val f = */ cursor.step { implicit tx =>
      implicit val n      = Nuages[S]
      implicit val aural  = AuralSystem()

      registerProcesses(sCfg, nCfg)

      import de.sciss.synth.proc.WorkspaceHandle.Implicits._
      val view  = NuagesView(n, nCfg, sCfg)
      /* val frame = */ NuagesFrame(view, undecorated = true)
      aural.start(aCfg)
    }
  }
}