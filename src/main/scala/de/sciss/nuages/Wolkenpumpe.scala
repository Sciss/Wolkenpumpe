/*
 *  Wolkenpumpe.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2017 Hanns Holger Rutz. All rights reserved.
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
import de.sciss.synth.proc.{AuralSystem, Code, Compiler, SoundProcesses}
import de.sciss.synth.{Server => SServer}

import scala.concurrent.Future
import scala.util.{Failure, Success}

object Wolkenpumpe {
  // var ALWAYS_CONTROL = true

  def main(args: Array[String]): Unit = {
    type S = InMemory
    implicit val system = InMemory()
    val w = new Wolkenpumpe[S]
    val nuagesH = system.step { implicit tx => tx.newHandle(Nuages.timeline[S]) }
    w.run(nuagesH)
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
      val amp = pAudio("amp", ParamSpec(-inf, 20, DbFaderWarp), Double.NegativeInfinity).dbamp
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

  def init(): Unit = {
    SoundProcesses.init()
    ParamSpec     .init()
    Warp          .init()
    Nuages        .init()
  }
}
class Wolkenpumpe[S <: Sys[S]] {
  private[this] var _view : NuagesView[S] = _
  private[this] var _aural: AuralSystem   = _

  def view: NuagesView[S] = {
    if (_view == null) throw new IllegalStateException(s"NuagesView not yet initialized")
    _view
  }

  def auralSystem: AuralSystem = {
    if (_aural == null) throw new IllegalStateException(s"AuralSystem not yet initialized")
    _aural
  }

  /** Subclasses may want to override this. */
  protected def configure(sCfg: ScissProcs.ConfigBuilder, nCfg: Nuages.ConfigBuilder,
                          aCfg: SServer.ConfigBuilder): Unit = {
    nCfg.masterChannels     = Some(0 to 7) // Vector(0, 1))
    nCfg.soloChannels       = None // Some(0 to 1)

    nCfg.micInputs          = Vector(
      NamedBusConfig("m-dpa"  , 0 until 1),
      NamedBusConfig("m-at "  , 3 until 4)
    )
    nCfg.lineInputs         = Vector(NamedBusConfig("pirro", 2 until 3))
    nCfg.lineOutputs        = Vector(
      NamedBusConfig("sum", 5 until 6)
      // , NamedBusConfig("hp", 6, 2)  // while 'solo' doesn't work
    )

    sCfg.audioFilesFolder   = Some(userHome / "Music" / "tapes")
  }

  /** Subclasses may want to override this. */
  protected def registerProcesses(nuages: Nuages[S], nCfg: Nuages.Config, sCfg: ScissProcs.Config)
                                 (implicit tx: S#Tx, cursor: stm.Cursor[S],
                                  compiler: Code.Compiler): Future[Unit] = {
    val futActions = ScissProcs.compileActions[S]()
    import SoundProcesses.executionContext
    futActions.map { actions =>
      cursor.step { implicit tx =>
        ScissProcs[S](nuages, nCfg, sCfg, actions)
      }
      ()
    }
  }

  def run(nuagesH: stm.Source[S#Tx, Nuages[S]])(implicit cursor: stm.Cursor[S]): Unit = {
    Wolkenpumpe.init()

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

    val maxInputs   = ((nCfg.lineInputs ++ nCfg.micInputs).map(_.stopOffset) :+ 0).max
    val maxOutputs  = (
      nCfg.lineOutputs.map(_.stopOffset) :+ nCfg.soloChannels.fold(0)(_.max + 1) :+ nCfg.masterChannels.fold(0)(_.max + 1)
      ).max
    println(s"numInputs = $maxInputs, numOutputs = $maxOutputs")

    aCfg.outputBusChannels  = maxOutputs
    aCfg.inputBusChannels   = maxInputs

    implicit val compiler: Code.Compiler = Compiler()

    val futPrep = cursor.step { implicit tx =>
      val n = nuagesH()
      _aural = AuralSystem()
      registerProcesses(n, nCfg, sCfg)
    }

    import SoundProcesses.executionContext
    futPrep.onComplete {
      case Success(_) =>
        cursor.step { implicit tx =>
          val n = nuagesH()
          import de.sciss.synth.proc.WorkspaceHandle.Implicits._
          implicit val aural = _aural
            _view = NuagesView(n, nCfg)
          /* val frame = */ NuagesFrame(_view, undecorated = false /* true */)
          aural.start(aCfg)
        }

      case Failure(ex) =>
        Console.err.println("Wolkenpumpe, failed to initialize:")
        ex.printStackTrace()
    }
  }
}