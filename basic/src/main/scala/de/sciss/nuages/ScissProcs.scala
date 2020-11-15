/*
 *  ScissProcs.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2020 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.file._
import de.sciss.lucre.{Artifact => _Artifact, ArtifactLocation => _ArtifactLocation}
import de.sciss.lucre.synth.Txn
import de.sciss.lucre.{Txn => LTxn}
import de.sciss.synth
import de.sciss.audiofile.AudioFile
import de.sciss.numbers.Implicits._
import de.sciss.proc
import de.sciss.proc.MacroImplicits.ActionMacroOps
import de.sciss.synth.{Curve, GE, doNothing}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.language.implicitConversions

/** This is my personal set of generators and filters. */
object ScissProcs {
  private[nuages] def any2stringadd: Any = ()

  trait ConfigLike {
    def audioFilesFolder: Option[File]

    /** Indices are 'secondary', i.e. they index the logical main channels,
      * not physical channels.
      */
    def mainGroups: Vec[NamedBusConfig]

    // set to zero to switch off
    def highPass: Int

    /** Force the number of channels in the generator, where `<= 0` indicates no forcing. */
    def genNumChannels: Int

    def recDir: File

    /** Whether to create procs that require third-party UGens (sc3plugins). */
    def plugins: Boolean
  }

  object Config {
    implicit def build(b: ConfigBuilder): Config = b.build

    def apply(): ConfigBuilder = new ConfigBuilder
  }

  sealed trait Config extends ConfigLike

  final class ConfigBuilder private[ScissProcs]() extends ConfigLike {
    var audioFilesFolder: Option[File]        = None
    var mainGroups    : Vec[NamedBusConfig] = Vector.empty
    var recDir          : File                = Util.defaultRecDir

    var genNumChannels = 0
    var highPass          = 0
    var plugins           = false

    def build: Config = ConfigImpl(
      audioFilesFolder = audioFilesFolder, mainGroups = mainGroups, genNumChannels = genNumChannels,
      highPass = highPass, recDir = recDir, plugins = plugins)
  }

  def actionRecPrepare[T <: LTxn[T]](implicit tx: T): proc.Action[T] = {
    val a = proc.Action[T]()
    import de.sciss.proc.ExImport._
    import de.sciss.lucre.expr.graph._
    a.setGraph {
      val ts      = TimeStamp()
      // N.B.: We currently cannot wait in the dispose action
      // for the file header to have been asynchronously flushed.
      // For AIFF it means, the spec says zero frames. We can
      // use the trick with the IRCAM file format, which has no
      // explicit number of frames, other than determined by its
      // file size.
      val name    = ts.format("'rec_'yyMMdd'_'HHmmss'.irc'")
      val artIn   = ArtifactLocation("value:$rec-dir")
      val artOut  = Artifact("value:$file")
      val artNew  = artIn / name
      // we don't need to run `ts.update`, because the action
      // is expanded and run only once, thus recreating a
      // fresh time stamp each time.
      Act(
        // ts.update,
        PrintLn("File to write: " ++ artNew.path),
        artOut.set(artNew)
//        artIn.set(artNew)
      )

      /*
      val obj     = invoker.getOrElse(sys.error("ScissProcs.recPrepare - no invoker"))
      val name    = recFormatAIFF.format(new java.util.Date)
      val nuages  = de.sciss.nuages.Nuages.find[S]()
        .getOrElse(sys.error("ScissProcs.recPrepare - Cannot find Nuages instance"))
      val loc     = getRecLocation(nuages, findRecDir(obj))
      val artM    = Artifact[S](loc, Artifact.Child(name)) // XXX TODO - should check that it is different from previous value
      obj.attr.put(attrRecArtifact, artM)
      */
    }
    a
  }

  def actionRecDispose[T <: LTxn[T]](implicit tx: T): proc.Action[T] = {
    val a = proc.Action[T]()
    import de.sciss.lucre.expr.graph._
    import de.sciss.proc.ExImport._
    a.setGraph {
      val artNew  = Artifact("value:$file")
      val specOpt = AudioFileSpec.Read(artNew)
      val procOpt = "play-template" .attr[Obj]
      val invOpt  = "invoker"       .attr[Obj]
      val actOpt  = for {
        spec    <- specOpt
        procTmp <- procOpt
        invoker <- invOpt
        gen     <- invoker.attr[Folder]("generators")
      } yield {
        val cue   = AudioCue(artNew, spec)
        val proc  = procTmp.copy
        Act(
          proc.make,
          proc.attr[AudioCue]("file").set(cue),
          proc.attr[String  ]("name").set(artNew.base),
          gen.append(proc),
          PrintLn("Cue: numFrames = " ++ cue.numFrames.toStr ++ ", numChannels = " ++ cue.numChannels.toStr),
        )
      }
      val actDone = actOpt.orElse {
        PrintLn("Could not create player! spec? " ++
          specOpt.isDefined.toStr ++
          ", proc? "   ++ procOpt.isDefined.toStr ++
          ", invoker? " ++ invOpt.isDefined.toStr)
      }

      //      val obj       = invoker.getOrElse(sys.error("ScissProcs.recDispose - no invoker"))
//      val attr      = obj.attr
//      val artObj    = attr.$[Artifact](attrRecArtifact).getOrElse(
//        sys.error(s"ScissProcs.recDispose - Could not find $attrRecArtifact"))
//      val genChans  = attr.$[IntObj  ](attrRecGenChans).fold(0)(_.value)
//      val nuages0 = de.sciss.nuages.Nuages.find[S]()
//        .getOrElse(sys.error("ScissProcs.recDispose - Cannot find Nuages instance"))
//      val nuagesH   = tx.newHandle(nuages0)
//      SoundProcesses.scheduledExecutorService.schedule(new Runnable {
//        def run(): Unit = SoundProcesses.atomic[S, Unit] { implicit tx =>
//          val nuages = nuagesH()
//          mkLoop[S](nuages, artObj, generatorChannels = genChans)
//        } (universe.cursor)
//      }, 1000, java.util.concurrent.TimeUnit.MILLISECONDS)
      Act(
        PrintLn("File written: " ++ artNew.toStr),
        actDone
      )
    }
    a
  }

  private case class ConfigImpl(audioFilesFolder: Option[File], mainGroups: Vec[NamedBusConfig],
                                genNumChannels: Int, highPass: Int, recDir: File, plugins: Boolean)
    extends Config

//  private final val attrPrepareRec  = "prepare-rec"
//  private final val attrDisposeRec  = "dispose-rec"

  def apply[T <: Txn[T]](nuages: Nuages[T], nConfig: Nuages.Config, sConfig: ScissProcs.Config)
                        (implicit tx: T): Unit = {
    val mapActions = mkActions[T]()
    applyWithActions[T](nuages, nConfig, sConfig, mapActions)
  }

  final val keyActionRecPrepare = "rec-prepare"
  final val keyActionRecDispose = "rec-dispose"

  def mkActions[T <: Txn[T]]()(implicit tx: T): Map[String, proc.Action[T]] = {
    val recPrepare = actionRecPrepare[T]
    val recDispose = actionRecDispose[T]
    Map(keyActionRecPrepare -> recPrepare, keyActionRecDispose -> recDispose)
  }

  def applyWithActions[T <: Txn[T]](nuages: Nuages[T], nConfig: Nuages.Config, sConfig: ScissProcs.Config,
                                    actions: Map[String, proc.Action[T]])
                                   (implicit tx: T): Unit = {
    import synth.ugen._
    import synth.inf

    val dsl = DSL[T]
    import dsl._
    import sConfig.genNumChannels

    val mainChansOption = nConfig.mainChannels

    def ForceChan(in: GE): GE = if (genNumChannels <= 0) in else {
      Util.wrapExtendChannels(genNumChannels, in)
    }

    implicit val _nuages: Nuages[T] = nuages

    def filterF   (name: String)(fun: GE => GE): proc.Proc[T] =
      filter      (name, if (DSL.useScanFixed) genNumChannels else -1)(fun)

    def sinkF     (name: String)(fun: GE => Unit): proc.Proc[T] =
      sink        (name, if (DSL.useScanFixed) genNumChannels else -1)(fun)

    def collectorF(name: String)(fun: GE => Unit): proc.Proc[T] =
      collector   (name, if (DSL.useScanFixed) genNumChannels else -1)(fun)

    def mkSpread(arg: GE)(gen: GE => GE): GE = {
      val nM = gen(arg)
      if (genNumChannels <= 0) nM else {
        val spread    = pAudio("spread", ParamSpec(0.0, 1.0), default(0.0f))
        val spreadAvg = Mix.mono(spread) / genNumChannels
        val pan       = spreadAvg.linLin(0, 1, -1, 1)
        val argAvg    = Mix.mono(arg) / genNumChannels
        val n0        = gen(argAvg)
        LinXFade2.ar(n0, nM, pan)
      }
    }

    // -------------- GENERATORS --------------

    sConfig.audioFilesFolder.foreach { folder =>
      val loc = _ArtifactLocation.newConst[T](folder.toURI)

      // N.B. do not use ellipsis character (…) because synth-def names are ASCII-7
      def abbreviate(s: String) = if (s.length < 16) s else s"${s.take(7)}...${s.takeRight(7)}"

      val audioFiles = folder.children
      audioFiles.filter(AudioFile.identify(_).isDefined).foreach(f => {
        val name = s"t-${abbreviate(f.base)}"

        val procObj = generator(name) {
          val p1    = pAudio("speed", ParamSpec(0.1f, 10f, ExpWarp), default(1.0))
          val speed = Mix.mono(p1) / NumChannels(p1)  // mean
          val disk  = synth.proc.graph.VDiskIn.ar("file", speed = speed, loop = 1)
          //          val b     = bufCue(path)
          //          val disk  = VDiskIn.ar(b.numChannels, b.id, p1.ar * BufRateScale.ir(b.id), loop = 1)
          // HPF.ar( disk, 30 )
          val sig   = ForceChan(disk)
          sig
        }

        val art   = _Artifact(loc, f.toURI) // loc.add(f)
        val spec  = AudioFile.readSpec(f)
        val gr    = proc.AudioCue.Obj[T](art, spec, 0L, 1.0)
        procObj.attr.put("file", gr)
      })
    }

    mainChansOption.foreach { mainChans =>
      val numChannels = mainChans.size

      generator("(test)") {
        val pAmp  = pControl("amp" , ParamSpec(0.01,  1.0, ExpWarp),  default(1.0))
        val pSig  = pControl("sig" , /* ParamSpec(0, 2, IntWarp) */ TrigSpec, default(0.0))
        val pFreq = pControl("freq", ParamSpec(0.1 , 10  , ExpWarp),  default(1.0))

        val idx       = Stepper.kr(Impulse.kr(pFreq), lo = 0, hi = numChannels)
        val sig0: GE  = Seq(WhiteNoise.ar(1), SinOsc.ar(441))
        val sig       = Select.ar(pSig, sig0) * pAmp
        val sigOut: GE = Seq.tabulate(numChannels)(ch => sig.out(ch) * (1 - (ch - idx.out(ch)).abs.min(1)))
        sigOut
      }
    }

    def default(in: Double): ControlValues =
      if (genNumChannels <= 0)
        in
      else
        Vector.fill(genNumChannels)(in)

    nConfig.micInputs.foreach { cfg =>
      if (cfg.name != NamedBusConfig.Ignore) generator(cfg.name) {
        val pBoost    = pAudio("gain", ParamSpec(0.1, 10, ExpWarp), default(0.1))
        val pFeed0    = pAudio("feed", ParamSpec(0, 1), default(0.0))

        val pFeed     = Mix.mono(pFeed0) / NumChannels(pFeed0)
        // val boost     = Mix.mono(pBoost) / NumChannels(pBoost)
        val boost     = pBoost
//        val pureIn    = In.ar(NumOutputBuses.ir + cfg.offset, cfg.numChannels) * boost
        val pureIn    = PhysicalIn.ar(cfg.indices) * boost
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
        val dly   = DelayC.ar(outs, 0.0125, LFDNoise1.kr(5).mulAdd(0.006, 0.00625))
        val feed  = pFeed * 2 - 1
        val sig   = XFade2.ar(pureIn, dly, feed)

        // val numOut = mainChansOption.fold(2)(_.size)
        val numOut = if (genNumChannels <= 0) mainChansOption.fold(2)(_.size) else genNumChannels

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

    nConfig.lineInputs.foreach { cfg =>
      if (cfg.name != NamedBusConfig.Ignore) generator(cfg.name) {
        val pBoost = pAudio("gain", ParamSpec(0.1, 10, ExpWarp), default(1.0))

        val boost   = pBoost
//        val sig     = In.ar(NumOutputBuses.ir + cfg.offset, cfg.numChannels) * boost
        val sig     = PhysicalIn.ar(cfg.indices) * boost
        // val numOut  = mainChansOption.fold(2)(_.size)
        val numOut  = if (genNumChannels <= 0) mainChansOption.fold(2)(_.size) else genNumChannels

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
      shortcut = "D"
      val pFreq   = pAudio("freq" , ParamSpec(0.01, 1000, ExpWarp), default(1.0 ))
      val pDecay  = pAudio("decay", ParamSpec(0.01,   10, ExpWarp), default(0.01))

      val freq = pFreq
      // val freq  = ForceChan(freq0)
      Decay.ar(Dust2.ar(freq), pDecay)
    }

    generator("~gray") {
      val pAmp  = pAudio("amp", ParamSpec(0.01, 1, ExpWarp), default(0.1))
      val amp   = pAmp
      val out   = GrayNoise.ar(amp)
      // out.poll(1, "gray")
      out
    }

    generator("~sin") {
      shortcut = "S"
      val freq  = pAudio("freq", ParamSpec(0.1 , 10000, ExpWarp), default(15.0))
      val pAmp  = pAudio("amp" , ParamSpec(0.01,     1, ExpWarp), default( 0.1))
      val out   = SinOsc.ar(freq) * pAmp
      // out.poll(1, "sin")
      out
    }

    generator("~pulse") {
      shortcut = "P"
      val freq  = pAudio("freq" , ParamSpec(0.1 , 10000, ExpWarp), default(15.0))
      val width = pAudio("width", ParamSpec(0.0 ,     1.0),        default( 0.5))
      val pAmp  = pAudio("amp"  , ParamSpec(0.01,     1, ExpWarp), default( 0.1))

      val sig   = Pulse.ar(freq, width)
      sig * pAmp
    }

    generator("~dc") {
      val sig = pAudio("value", ParamSpec(0, 1), default(0.0))
      sig
    }

    generator("a~noise0") {
      val freq  = pAudio("freq", ParamSpec(0.01, 1000.0, ExpWarp, unit = "Hz"), default(1.0f))
      val lo    = pAudio("lo"  , ParamSpec(0.0, 1.0), default(0.0f))
      val hi    = pAudio("hi"  , ParamSpec(0.0, 1.0), default(1.0f))
      //      val pow   = pAudio("pow" , ParamSpec(0.125, 8, ExpWarp), default(1.0))
      mkSpread(freq)(LFDNoise0.ar).linLin(-1, 1, lo, hi) // .pow(pow)
    }

    generator("a~noise1") {
      val freq  = pAudio("freq", ParamSpec(0.01, 1000.0, ExpWarp, unit = "Hz"), default(1.0f))
      val lo    = pAudio("lo"  , ParamSpec(0.0, 1.0), default(0.0f))
      val hi    = pAudio("hi"  , ParamSpec(0.0, 1.0), default(1.0f))
      //      val pow   = pAudio("pow" , ParamSpec(0.125, 8, ExpWarp), default(1.0))
      mkSpread(freq)(LFDNoise1.ar).linLin(-1, 1, lo, hi) // .pow(pow)
    }

    generator("a~brown") {
      val up      = pAudio("up"  , ParamSpec(0.1, 10000.0, ExpWarp), default(1.0f))
      val down    = pAudio("down", ParamSpec(0.1, 10000.0, ExpWarp), default(1.0f))
      val n       = mkSpread(default(1f).seq)(BrownNoise.ar)
      val range   = n.linLin(-1, 1, 0, 1)
      Slew.ar(range, up = up, down = down)
    }

    // -------------- FILTERS --------------

    def mix(in: GE, flt: GE, mix: GE): GE = LinXFade2.ar(in, flt, mix * 2 - 1)
    def mkMix(df: Double = 0.0): GE = pAudio("mix", ParamSpec(0, 1), default(df))

    // a 10% direct fade-in/out, possibly with delay to compensate for FFT
    def mkBlend(pred: GE, z: GE, fade: GE, dt: GE = Constant(0)): GE = {
      import de.sciss.synth._
      import de.sciss.synth.ugen._
      val dpa = fade.min(0.1) * 10
      val pa  = fade.min(0.1).linLin(0, 0.1, 1, 0)
      val za  = 1 - pa
      val dp  = if (dt == Constant(0)) pred else DelayN.ar(pred, dt, dt * dpa)
      val pm  = dp * pa
      val zm  = z  * za
      pm + zm
    }

    filterF("staub") { in =>
      val pAmt      = pAudio("amt" , ParamSpec(0.0, 1.0), default(1.0))
      val pMix      = mkMix()

      val f1        =   10
      val f2        = 2000

      // val relIdx    = ChannelIndices(in) / (NumChannels(in) - 1).max(1)
      // val fade0     = pAmt * relIdx.linLin(0, 1, 1, pFact)
      // val fade      = fade0.clip(0, 1)
      val fade      = pAmt // fade0.max(0).min(1) // some crazy bug in Clip
      val dustFreqS = fade.linExp(0, 1, f1, f2)
      // val dustFreqP = fade.linExp(1, 0, f1, f2)

      val decayTime = 0.01
      val dustS     = Decay.ar(Dust.ar(dustFreqS), decayTime).min(1)
      // val dustP = Decay.ar(Dust.ar(dustFreqP), decayTime).min(1)

      val flt       = in * dustS

      mix(in, flt, pMix)
    }

    filterF("delay") { in =>
      val pTime       = pAudio("time", ParamSpec(0.03, 30.0, ExpWarp), default(10.0))
      val pFeed       = pAudio("feed", ParamSpec(0.001, 1.0, ExpWarp), default(0.001))
      val pMix        = mkMix()

      val numFrames   = SampleRate.ir * 30
      val buf         = LocalBuf(numFrames = numFrames, numChannels = Pad(1, in))
      ClearBuf(buf)
      val time        = Lag.ar(pTime)
      val lin         = LocalIn.ar(Pad(0, in)) // Pad.LocalIn.ar(in)
      val feed        = pFeed
      val wDry        = (1 - feed).sqrt
      val wWet        = feed.sqrt
      val flt0        = BufDelayL.ar(buf, (in * wDry) + (lin * wWet), time)
      val flt         = LeakDC.ar(flt0)
      LocalOut.ar(flt)

      mix(in, flt, pMix)
    }

    filterF("mantissa") { in =>
      val pBits = pAudio("bits", ParamSpec(2, 14, IntWarp), default(14.0))
      val pMix  = mkMix()

      val flt = MantissaMask.ar(in, pBits)
      mix(in, flt, pMix)
    }

    filterF("achil") { in =>
      shortcut = "A"
      val pSpeed      = pAudio("speed", ParamSpec(0.125, 2.3511, ExpWarp), default(0.5))
      val pMix        = mkMix()

      // proc.graph.Buffer.apply()

      val speed       = Lag.ar(pSpeed, 0.1)
      val numFrames   = SampleRate.ir // sampleRate.toInt
      val bufID       = LocalBuf(numFrames = numFrames, numChannels = Pad(1, in))
      ClearBuf(bufID)
      val writeRate   = BufRateScale.kr(bufID)
      val readRate    = writeRate * speed
      val readPhasor  = Phasor.ar(0, readRate, 0, numFrames)
      val read0       = BufRd.ar(1, bufID, readPhasor, 0, 4)
      // val readBad     = CheckBadValues.ar(read0, id = 1000)
      val read        = read0 // Gate.ar(read0, readBad sig_== 0)

      val writePhasor = Phasor.ar(0, writeRate, 0, numFrames)
      val old         = BufRd.ar(1, bufID, writePhasor, 0, 1)
      val wet0        = SinOsc.ar(0, (readPhasor - writePhasor).abs / numFrames * math.Pi)
      val dry         = 1 - wet0.squared
      val wet         = 1 - (1 - wet0).squared
      val write0      = (old * dry) + (in * wet)
      // val writeBad    = CheckBadValues.ar(write0, id = 1001)
      val writeSig    = write0 // Gate.ar(write0, writeBad sig_== 0)

      // NOTE: `writeSig :: Nil: GE` does _not_ work because single
      // element seqs are not created by that conversion.
      BufWr.ar(Pad.Split(writeSig), bufID, writePhasor)
      mix(in, read, pMix)
    }

    filterF("a-gate") { in =>
      val pAmt = pAudio("amt", ParamSpec(0, 1), default(1.0))
      val pMix = mkMix()

      val amount  = Lag.ar(pAmt, 0.1)
      val flt     = Compander.ar(in, in, Amplitude.ar(in * (1 - amount) * 5), 20, 1, 0.01, 0.001)
      mix(in, flt, pMix)
    }

    filterF("a-hilb") { in =>
      val pMix = mkMix()
      val hlb   = Hilbert.ar(DelayN.ar(in, 0.01, 0.01))
      val hlb2  = Hilbert.ar(Normalizer.ar(in, dur = 0.02))
      val flt   = hlb.real * hlb2.real - hlb.imag * hlb2.imag
      val out   = mix(in, flt, pMix)
      out
    }

    filterF("hilbert") { in =>
      shortcut = "H"
      val pFreq = pAudio("freq", ParamSpec(-1, 1), default(0.0))
      val pMix  = mkMix()

      val freq    = pFreq
      val freqHz  = freq.abs.linExp(0, 1, 20, 12000) * freq.signum
      val flt     = FreqShift.ar(in, freqHz)
      mix(in, flt, pMix)
    }

    filterF("reso") { in =>
      shortcut = "R"
      val pFreq   = pAudio("freq", ParamSpec(30  , 13000, ExpWarp), default(400.0)) // beware of the upper frequency
      val pq      = pAudio("q"   , ParamSpec( 0.5,    50, ExpWarp), default(  1.0))
      val pMix    = mkMix()

      val freq   = pFreq
      // val freq    = freq0 :: (freq0 * pFreq2).max(30).min(13000) :: Nil
      val rq      = pq.reciprocal
      val makeUp  = pq // (rq + 0.5).pow(1.41) // rq.max( 1 ) // .sqrt
      val flt     = Resonz.ar(in, freq, rq) * makeUp
      mix(in, flt, pMix)
    }

    filterF("notch") { in =>
      shortcut = "N"
      val pFreq   = pAudio("freq", ParamSpec(30, 16000, ExpWarp), default(400.0))
      val pq      = pAudio("q", ParamSpec(1, 50, ExpWarp), default(1.0)) // beware of the lower q
      val pMix    = mkMix()

      val freq    = pFreq
      // val freq    = freq0 :: (freq0 * pFreq2).max(30).min(16000) :: Nil
      val rq      = pq.reciprocal
      val flt     = BRF.ar(in, freq, rq)
      mix(in, flt, pMix)
    }

    filterF("filt") { in =>
      shortcut = "F"
      val pFreq = pAudio("freq", ParamSpec(-1, 1), default(0.0))
      val pMix  = mkMix(1.0)

      val normFreq  = pFreq
      val lowFreqN  = Lag.ar(Clip.ar(normFreq, -1, 0))
      val highFreqN = Lag.ar(Clip.ar(normFreq,  0, 1))
      val lowFreq   = LinExp.ar(lowFreqN, -1, 0, 30, 20000)
      val highFreq  = LinExp.ar(highFreqN, 0, 1, 30, 20000)
      val lowMix    = Clip.ar(lowFreqN  * -10.0, 0, 1)
      val highMix   = Clip.ar(highFreqN * +10.0, 0, 1)
      val dryMix    = 1 - (lowMix + highMix)
      val lpf       = LPF.ar(in, lowFreq ) * lowMix
      val hpf       = HPF.ar(in, highFreq) * highMix
      val dry       = in * dryMix
      val flt       = dry + lpf + hpf
      mix(in, flt, pMix)
    }

    filterF("frgmnt") { in =>
      val pSpeed      = pAudio  ("speed", ParamSpec(0.125, 2.3511, ExpWarp), default(1.0))
      val pGrain      = pControl("grain", ParamSpec(0, 1), default(0.5))
      val pFeed       = pAudio  ("fb"   , ParamSpec(0, 1), default(0.0))
      val pMix        = mkMix()

      val bufDur      = 4.0
      val numFrames   = bufDur * SampleRate.ir
      //      val numChannels = in.numChannels // numOutputs
      //      val buf         = bufEmpty(numFrames, numChannels)
      //      val bufID       = buf.id
      val buf         = LocalBuf(numFrames = numFrames, numChannels = Pad(1, in))
      ClearBuf(buf)

      val feedBack    = Lag.ar(pFeed, 0.1)
      val grain       = pGrain // Lag.kr( grainAttr.kr, 0.1 )
      val maxDur      = LinExp.kr(grain, 0, 0.5, 0.01, 1.0)
      val minDur      = LinExp.kr(grain, 0.5, 1, 0.01, 1.0)
      val fade        = LinExp.kr(grain, 0, 1, 0.25, 4)
      val rec         = (1 - feedBack).sqrt
      val pre         = feedBack.sqrt

      // val numChans    = if (generatorChannels > 0) generatorChannels else 1

      val trig        = LocalIn.kr(Pad(0, pSpeed)) // Pad.LocalIn.kr(pSpeed)

      val white       = TRand.kr(0, 1, trig)
      val dur         = LinExp.kr(white, 0, 1, minDur, maxDur)
      val off0        = numFrames * white
      val off         = off0 - (off0 % 1.0)
      val gate        = trig
      val lFade       = Latch.kr(fade, trig)
      val fadeIn      = lFade * 0.05
      val fadeOut     = lFade * 0.15
      val env         = EnvGen.ar(Env.linen(fadeIn, dur, fadeOut, 1, Curve.sine), gate, doneAction = doNothing)
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
      // val writeBad    = CheckBadValues.ar(write0, id = 2001)
      val writeSig    = write0 // Gate.ar(write0, writeBad sig_== 0)

      // writeSig.poll(1, "write")
      val writeSigS = Pad.Split(writeSig)
      BufWr.ar(writeSigS, buf = buf, index = writeIdx, loop = 1)

      LocalOut.kr(Impulse.kr(1.0 / (dur + fadeIn + fadeOut).max(0.01)))

      // NOTE: PlayBuf doesn't seem to work with LocalBuf !!!

      val speed       = pSpeed
      // val play0       = PlayBuf.ar(1 /* numChannels */, buf, speed, loop = 1)
      val readIdx     = Phasor.ar(speed = speed, lo = 0, hi = numFrames)
      val read0       = BufRd.ar(1, buf = buf, index = readIdx, loop = 1)
      // val readBad     = CheckBadValues.ar(read0, id = 2000)
      val play0       = read0 // Gate.ar(read0, readBad sig_== 0)
      val play        = Flatten(play0)

      // play.poll(1, "outs")
      mix(in, play, pMix)
    }

    filterF("*") { in =>
      shortcut = "M" // does not work: "ASTERISK"
      val pMix  = mkMix()
      val in2   = /*pAudioIn("in_m")*/ pAudio("mod", ParamSpec(0 /* -1 */, 1), default(0.0))
      val pLag  = pAudio("lag", ParamSpec(0.001, 0.1, ExpWarp), default(0.001))
      val flt   = in * Lag.ar(in2, pLag - 0.001)
      mix(in, flt, pMix)
    }

    filterF("gain") { in =>
      shortcut = "G"
      val pGain = pAudio("gain", ParamSpec(-30, 30), default(0.0))
      val pMix  = mkMix(1.0)

      val amp = pGain.dbAmp
      val flt = in * amp
      mix(in, flt, pMix)
    }

    filterF("gendy") { in =>
      val pAmt    = pAudio("amt", ParamSpec(0, 1), default(0.0))
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

    filterF("~skew") { in =>
      val pLo     = pAudio("lo" , ParamSpec(0, 1), default(0.0))
      val pHi     = pAudio("hi" , ParamSpec(0, 1), default(1.0))
      val pPow    = pAudio("pow", ParamSpec(0.125, 8, ExpWarp), default(1.0))
      val pRound  = pAudio("rnd", ParamSpec(0, 1), default(0.0))

      val pMix    = mkMix()

      val sig = in.clip2(1).linLin(-1, 1, pLo, pHi).pow(pPow).roundTo(pRound) * 2 - 1
      mix(in, sig, pMix)
    }

    filterF("~onsets") { in =>
      val pThresh     = pControl("thresh", ParamSpec(0, 1), default(0.5))
      val pDecay      = pAudio  ("decay" , ParamSpec(0, 1), default(0.0))

      val pMix        = mkMix()

      //      val numChannels = in.numChannels // numOutputs
      //      val bufIDs      = Seq.fill(numChannels)(bufEmpty(1024).id)
      val bufIDs      = LocalBuf(numFrames = 1024, numChannels = Pad(1, in))
      ClearBuf(bufIDs)
      val chain1      = FFT(bufIDs, in)
      val onsets      = Onsets.kr(chain1, pThresh)
      val sig         = Decay.ar(Trig1.ar(onsets, SampleDur.ir), pDecay).min(1) // * 2 - 1
      mix(in, sig, pMix)
    }

    filterF("m-above") { in =>
      val pThresh = pAudio("thresh", ParamSpec(1.0e-3, 1.0e-0, ExpWarp), default(1.0e-2))
      val pMix    = mkMix()

      val thresh  = A2K.kr(pThresh)
      val env     = Env(0.0, Seq(Env.Segment(0.2, 0.0, Curve.step), Env.Segment(0.2, 1.0, Curve.lin)))
      val ramp    = EnvGen.kr(env)
      val volume  = thresh.linLin(1.0e-3, 1.0e-0, 4 /* 32 */, 2)
      val bufIDs  = LocalBuf(numFrames = 1024, numChannels = Pad(1, in))
      ClearBuf(bufIDs)
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

    filterF("m-below") { in =>
      val pThresh     = pAudio("thresh", ParamSpec(1.0e-1, 10.0, ExpWarp), default(1.0))
      val pMix        = mkMix()

      val thresh      = A2K.kr(pThresh)
      val env         = Env(0.0, Seq(Env.Segment(0.2, 0.0, Curve.step), Env.Segment(0.2, 1.0, Curve.lin)))
      val ramp        = EnvGen.kr(env)
      //            val volume		   = LinLin.kr( thresh, 1.0e-2, 1.0e-0, 4, 1 )
      val volume      = thresh.linLin(1.0e-1, 10, 2, 1)
      val bufIDs      = LocalBuf(numFrames = 1024, numChannels = Pad(1, in))
      ClearBuf(bufIDs)
      val chain1      = FFT(bufIDs, in)
      val chain2      = PV_MagBelow(chain1, thresh)
      val flt         = volume * IFFT.ar(chain2) * ramp

      // account for initial dly
      val env2 = Env(0.0, Seq(Env.Segment(BufDur.kr(bufIDs) * 2, 0.0, Curve.step), Env.Segment(0.2, 1, Curve.lin)))
      val wet = EnvGen.kr(env2)
      val sig = (in * (1 - wet).sqrt) + (flt * wet)
      mix(in, sig, pMix)
    }

    filterF("pitch") { in =>
      val pTrans  = pAudio("shift", ParamSpec(0.125, 4, ExpWarp), default(1.0 ))
      val pTime   = pAudio("time" , ParamSpec(0.01,  1, ExpWarp), default(0.01))
      val pPitch  = pAudio("pitch", ParamSpec(0.01,  1, ExpWarp), default(0.01))
      val pMix    = mkMix()

      val grainSize     = 0.5f
      val pitch         = A2K.kr(pTrans)
      val timeDisperse  = A2K.kr(pTime )
      val pitchDisperse = A2K.kr(pPitch)
      val flt0          = PitchShift.ar(in, grainSize, pitch, pitchDisperse, timeDisperse * grainSize)
      val gain          = 2.5 - pMix.max(0.5)
      val flt           = flt0 * gain // compensate for gain (approximate)
      mix(in, flt, pMix)
    }

    filterF("pow") { in =>
      val exp   = pAudio("amt"  , ParamSpec(0.5, 2.0, ExpWarp), default(1.0))
      val decay0= pAudio("decay", ParamSpec(0.5, 1.0, ExpWarp), default(1.0))
      val decay = Lag.ar(decay0) // Lag.kr(A2K.kr(decay0), 1) // Lag.ar(decay0)
      val peak0 = PeakFollower.ar(in, decay)
      CheckBadValues.ar(peak0, id = 777)
      val peak  = peak0.max(-20.dbAmp)
      val pMix  = mkMix()
      val inN   = in / peak
      val pow   = inN.pow(exp)
      val flt   = pow * peak
      mix(in, flt, pMix)
    }

    filterF("renoise") { in =>
      val pColor      = pAudio("color", ParamSpec(0, 1), default(1.0))
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

    filterF("verb") { in =>
      val pExtent = pControl("size" , ParamSpec(0, 1), default(0.5))
      val pColor  = pControl("color", ParamSpec(0, 1), default(0.5))
      val pMix    = mkMix()

      val extent      = pExtent
      val color       = Lag.kr(pColor, 0.1)
      val i_roomSize  = LinExp.kr(extent, 0, 1, 1, 100)
      val i_revTime   = LinExp.kr(extent, 0, 1, 0.3, 20)
      val spread      = 15

      val fltS        = GVerb.ar(in, i_roomSize, i_revTime, color, color, spread, 0, 1, 0.7, i_roomSize) * 0.3
      val flt         = fltS out 0   // simply drop the right channels of each verb
      mix(in, flt, pMix)
    }

    filterF("zero") { in =>
      val pWidth  = pAudio("width", ParamSpec(0, 1), default(0.5))
      val pDiv    = pAudio("div"  , ParamSpec(1, 12, IntWarp), default(1.0))
      val pLag    = pAudio("lag"  , ParamSpec(0.001, 0.1, ExpWarp), default(0.01))
      val pMix    = mkMix()

      val lagTime = pLag
      val freq    = ZeroCrossing.ar(in).max(2)
      val width0  = Lag.ar(pWidth, lagTime)
      val width   = width0 // width0.reciprocal
      val div     = Lag.ar(pDiv, lagTime)
      val pulseF  = freq / div
      // pulseF.poll(1, "pulse")
      val pulse   = Lag.ar(LFPulse.ar(pulseF, width = width), lagTime)
      val amp     = Amplitude.kr(pulse).max(0.2).reciprocal
      // val amp     = PeakFollower.ar(pulse).max(0.1).reciprocal
      val flt     = in * pulse * amp
      mix(in, flt, pMix)
    }

    filterF("L-lpf") { in =>
      val fade  = mkMix() // mkMix4()
      val freq  = fade.linExp(1, 0, 22.05 * 2, 20000) // 22050
      val wet   = LPF.ar(in, freq)
      mkBlend(in, wet, fade)
    }

    filterF("L-hpf") { in =>
      val fade  = mkMix() // mkMix4()
      val freq  = fade.linExp(1, 0, 20000, 22.05 * 2)
      val wet   = HPF.ar(HPF.ar(in, freq), freq)
      mkBlend(in, wet, fade)
    }

    val FFTSize = 512

    filterF("L-below") { in =>
      val fade    = mkMix() // mkMix4()
      val thresh  = fade.linExp(1, 0, 1.0e-3, 1.0e1)
      val buf     = LocalBuf(FFTSize)
      val wet     = IFFT.ar(PV_MagBelow(FFT(buf, in), thresh))
      mkBlend(in, wet, fade, FFTSize / SampleRate.ir)
    }

    filterF("L-above") { in =>
      val fade    = mkMix() // mkMix4()
      val thresh  = fade.linExp(0, 1, 1.0e-3, 2.0e1)
      val buf     = LocalBuf(FFTSize)
      val wet     = IFFT.ar(PV_MagAbove(FFT(buf, in), thresh))
      mkBlend(in, wet, fade, FFTSize / SampleRate.ir)
    }

    filterF("L-up") { in =>
      val fade    = mkMix() // mkMix4()
      val numSteps = 16 // 10
      val x        = (1 - fade) * numSteps
      val xh       = x / 2
      val a        = (xh + 0.5).floor        * 2
      val b0       = (xh       .floor + 0.5) * 2
      val b        = b0.min(numSteps)
      val ny       = 20000 // 22050
      val zero     = 22.05
      val aFreq    = a.linExp(numSteps, 0, zero, ny) - zero
      val bFreq    = b.linExp(numSteps, 0, zero, ny) - zero
      val freq: GE = Seq(aFreq, bFreq)

      val z0      = FreqShift.ar(LPF.ar(in, ny - freq),  freq)

      val zig     = x.fold(0, 1)
      val az      = zig     // .sqrt
      val bz      = 1 - zig // .sqrt
      val wet     = az * (z0 out 1 /* aka ceil */) + bz * (z0 out 0 /* aka floor */)

      mkBlend(in, wet, fade)
    }

    filterF("L-down") { in =>
      val fade     = mkMix() // mkMix4()
      val numSteps = 16
      val x        = (1 - fade) * numSteps
      val xh       = x / 2
      val a        = (xh + 0.5).floor        * 2
      val b0       = (xh       .floor + 0.5) * 2
      val b        = b0.min(numSteps)
      val fd: GE   = Seq(a, b)
      val ny       = 20000 // 20000 // 22050
      val zero     = 22.05
      val freq1    = fd.linExp(0, numSteps, ny, zero)
      // val freq2    = fd.linexp(0, numSteps, zero, ny) - zero

      val fltSucc   = HPF.ar(in, freq1)
      val z0        = FreqShift.ar(fltSucc, -freq1)

      val zig = x.fold(0, 1)
      val az  = zig      // .sqrt
      val bz  = 1 - zig  // .sqrt
      val wet = az * (z0 out 1 /* aka ceil */) + bz * (z0 out 0 /* aka floor */)

      mkBlend(in, wet, fade)
    }

    filterF("env-perc") { in =>
      shortcut = "E"

      val attack  = pAudio("atk"   , ParamSpec(0.001,  1.0, ExpWarp), default(0.01))
      val release = pAudio("rls"   , ParamSpec(0.001, 10.0, ExpWarp), default(0.01))
      val curveP  = pAudio("curve" , ParamSpec(-4, 4), default(0.0))
      val level   = pAudio("amp"   , ParamSpec(0.01,  1.0, ExpWarp),  default(1.0))
      val thresh  = pAudio("thresh", ParamSpec(0, 1), default(0.5))
      val pMix    = mkMix()

      val trig    = in > thresh
      val curve   = Env.Curve(Curve.parametric.id, curveP)

      val env = Env.perc(attack = attack, release = release, level = level, curve = curve)
      val flt = EnvGen.ar(env, gate = trig)

      // gen // mkBlend(in, wet, fade)
      mix(in, flt, pMix)
    }

    filterF("z-onsets") { in =>
      val pThresh0    = pControl("thresh", ParamSpec(0, 1), default(0.5))
      val pAmp        = pAudio("amp"  , ParamSpec(0.01,     1, ExpWarp), default( 0.1))
      val pMix        = mkMix()
      val pThresh     = Mix.mono(pThresh0) / NumChannels(pThresh0)
      val buf         = LocalBuf(numFrames = 1024, numChannels = 1)
      val chain1      = FFT(buf, in)
      val onsets      = Onsets.kr(chain1, pThresh)
      val flt         = T2A.ar(onsets) * pAmp
      mix(in, flt, pMix)
    }

    filterF("z-centroid") { in =>
      val pLo         = pAudio("lo", ParamSpec(0.0 , 1), default(0.0))
      val pHi         = pAudio("hi", ParamSpec(0.0 , 1), default(1.0))
      val pMix        = mkMix()
      val buf         = LocalBuf(numFrames = 1024, numChannels = 1)
      val chain1      = FFT(buf, in)
      val cent        = SpecCentroid.kr(chain1)
      val centN       = cent.clip(100, 10000).expLin(100, 10000, pLo, pHi)
      val flt         = centN // T2A.ar(onsets) * pAmp
      mix(in, flt, pMix)
    }

    filterF("z-flat") { in =>
      val pLo         = pAudio("lo", ParamSpec(0.0 , 1), default(0.0))
      val pHi         = pAudio("hi", ParamSpec(0.0 , 1), default(1.0))
      val pMix        = mkMix()
      val buf         = LocalBuf(numFrames = 1024, numChannels = 1)
      val chain1      = FFT(buf, in)
      val flat        = SpecFlatness.kr(chain1)
      val flatN       = flat.clip(0, 1).linLin(0, 1, pLo, pHi)
      val flt         = flatN // T2A.ar(onsets) * pAmp
      mix(in, flt, pMix)
    }

    filterF("mono") { in =>
      val pMix  = mkMix()
      val flt   = Mix.mono(in) / NumChannels(in)
      mix(in, flt, pMix)
    }

    // -------------- SINKS --------------
    val sinkRec = sinkF("rec") { in =>
      val disk = synth.proc.graph.DiskOut.ar(Util.attrRecArtifact, in)
      disk.poll(0, "disk")
    }

    val sinkPrepObj = actions(keyActionRecPrepare)
    val sinkDispObj = actions(keyActionRecDispose)
//    val genChansObj = IntObj.newConst[S](genNumChannels)
    val recDirObj   = _ArtifactLocation.newConst[T](sConfig.recDir.toURI)

    // XXX TODO
    // we currently have to ex representation for nuages, perhaps
    // for good reason (is it really a good structure with the
    // fixed folders?). as a work around, to be able to access them
    // from the dispose action, we put the generators again into
    // the attribute map
    nuages.attr.put("generators", nuages.generators.getOrElse(throw new IllegalStateException()))

    if (genNumChannels > 0) {
      val pPlaySinkRec = Util.mkLoop(nuages, "play-sink", numBufChannels = genNumChannels, genNumChannels = genNumChannels)
      sinkDispObj.attr.put("play-template", pPlaySinkRec)
    }

    val sinkRecA = sinkRec.attr
    sinkRecA.put(Nuages.attrPrepare     , sinkPrepObj)
    sinkRecA.put(Nuages.attrDispose     , sinkDispObj)
//    sinkRecA.put(Util  .attrRecGenChannels , genChansObj)
    sinkRecA.put(Util  .attrRecDir      , recDirObj  )

    val sumRec = generator("rec-sum") {
      val numCh = mainChansOption.map(_.size).getOrElse(1)
      val in    = InFeedback.ar(0, numCh)   // XXX TODO --- should find correct indices!
      synth.proc.graph.DiskOut.ar(Util.attrRecArtifact, in)
      DC.ar(0)
    }
    val sumRecA = sumRec.attr
    sumRecA.put(Nuages.attrPrepare  , sinkPrepObj)
    sumRecA.put(Nuages.attrDispose  , sinkDispObj)
//    sumRecA.put(Util.attrRecGenChannels, genChansObj)
    sumRecA.put(Util.attrRecDir     , recDirObj  )

    // -------------- CONTROL SIGNALS --------------

    generator("a~pulse") {
      shortcut = "shift P"
      val pFreq   = pAudio("freq"   , ParamSpec(0.1 , 10000, ExpWarp), default(15.0))
      val pW      = pAudio("width"  , ParamSpec(0.0 ,     1.0),        default(0.5))
      val pLo     = pAudio("lo"     , ParamSpec(0.0 , 1), default(0.0))
      val pHi     = pAudio("hi"     , ParamSpec(0.0 , 1), default(1.0))

      val freq  = pFreq // LinXFade2.ar(pFreq, inFreq, pFreqMix * 2 - 1)
      val width = pW // LinXFade2.ar(pW, inW, pWMix * 2 - 1)
      val sig   = LFPulse.ar(freq, width = width)

      sig.linLin(0, 1, pLo, pHi)
    }

    generator("a~sin") {
      shortcut = "shift S"
      val pFreq   = pAudio("freq"   , ParamSpec(0.1 , 10000, ExpWarp), default(15.0))
      val pLo     = pAudio("lo"     , ParamSpec(0.0 , 1), default(0.0))
      val pHi     = pAudio("hi"     , ParamSpec(0.0 , 1), default(1.0))

      val freq  = pFreq // LinXFade2.ar(pFreq, inFreq, pFreqMix * 2 - 1)
      val sig   = SinOsc.ar(freq)

      sig.linLin(-1, 1, pLo, pHi)
    }

    generator("a~dust") {
      val pFreq   = pAudio("freq" , ParamSpec(0.01, 1000.0, ExpWarp), default(0.1))
      val pDecay  = pAudio("decay", ParamSpec(0.001 , 10.0, ExpWarp), default(0.1))
      val pLo     = pAudio("lo"   , ParamSpec(0.0   , 1.0          ), default(0.0))
      val pHi     = pAudio("hi"   , ParamSpec(0.0   , 1.0          ), default(1.0))

      val freq  = pFreq
      val sig   = Decay.ar(Dust.ar(freq), pDecay).clip(0.01, 1).linLin(0.01, 1, pLo, pHi)
      sig
    }

    generator("a~gray") {
      val pLo     = pAudio("lo"     , ParamSpec(0.0 , 1), default(0.0))
      val pHi     = pAudio("hi"     , ParamSpec(0.0 , 1), default(1.0))
      // GrayNoise.ar.linlin(-1, 1, pLo, pHi)
      val amp     = Pad(1.0, pLo)
      val out     = GrayNoise.ar(amp).linLin(-1, 1, pLo, pHi)
      // out.poll(1, "gray")
      out
    }

    filterF("a~rand") { in =>
      val pLo     = pAudio("lo"     , ParamSpec(0.0 , 1), default(0.0))
      val pHi     = pAudio("hi"     , ParamSpec(0.0 , 1), default(1.0))
      val pQuant  = pAudio("quant"  , ParamSpec(0.0 , 1), default(0.0))
      val pMix    = mkMix()
      val inTrig  = in - 0.001 // pAudio("tr", ParamSpec(0, 1), default(0.0))
      val sig0    = K2A.ar(TRand.kr(0 /* A2K.kr(pLo) */ , 1 /* A2K.kr(pHi) */, T2K.kr(inTrig)))
      val sig     = sig0.roundTo(pQuant).linLin(0, 1, pLo, pHi)
      mix(in, sig, pMix)
    }

    filterF("a~delay") { in =>
      val pTime   = pAudio("time", ParamSpec(0.0 , 1.0), default(0.0))
      val pMix    = mkMix(1.0)
      val sig     = DelayN.ar(in, delayTime = pTime, maxDelayTime = 1.0)
      mix(in, sig, pMix)
    }

    filterF("a~skew") { in =>
      shortcut = "K"
      val pLo     = pAudio("lo" , ParamSpec(0, 1), default(0.0))
      val pHi     = pAudio("hi" , ParamSpec(0, 1), default(1.0))
      val pPow    = pAudio("pow", ParamSpec(0.125, 8, ExpWarp), default(1.0))
      val pRound  = pAudio("rnd", ParamSpec(0, 1), default(0.0))

      val pMix    = mkMix()

      val sig = in.max(0).min(1).pow(pPow).linLin(0, 1, pLo, pHi).roundTo(pRound)
      mix(in, sig, pMix)
    }

    filterF("a~step8") { in =>
      val vals    = Vector.tabulate(8)(i => pAudio(s"v${i+1}", ParamSpec(0, 1), default(0.0)))
      val trig    = in - 0.001 // pAudio("trig", ParamSpec(0.0, 1.0), default(0.0))
      val hi      = pAudio("hi", ParamSpec(1, 8, IntWarp), default(1.0))
      val pMix    = mkMix()
      val index   = Stepper.ar(trig, lo = 0, hi = hi - 1)
      val sig     = Select.ar(index, vals)
      mix(in, sig, pMix)
    }

    filterF("a~dup") { in =>
      shortcut = "U"
      val pThresh = pAudio("thresh", ParamSpec(0.01, 1, ExpWarp), default(0.1))
      val pMul    = pAudio("mul"   , ParamSpec(1  , 16, IntWarp), default(1.0))
      val pDiv    = pAudio("div"   , ParamSpec(1  , 16, IntWarp), default(1.0))
      val pAmp    = pAudio("amp"   , ParamSpec(0.01, 1, ExpWarp), default(0.1))
      val pMix    = mkMix()
      val tr      = in > pThresh
      val tim     = Timer.ar(tr)
      val sd      = SampleDur.ir
      val frq     = tim.max(sd * 2).reciprocal * pMul / pDiv
      // frq.poll(1, "dup-freq")
      val sig     = Phasor.ar(in, frq * sd) * pAmp
      mix(in, sig, pMix)
    }

    filterF("a~div") { in =>
      shortcut = "V"
      val pThresh = pAudio("thresh", ParamSpec(0.01, 1, ExpWarp), default(0.1))
      val pMul    = pAudio("mul"   , ParamSpec(1  , 16, IntWarp), default(1.0))
      val pDiv    = pAudio("div"   , ParamSpec(1  , 16, IntWarp), default(1.0))
      val pAmp    = pAudio("amp"   , ParamSpec(0.01, 1, ExpWarp), default(0.1))
      val pMix    = mkMix()
      val inTrig  = in > pThresh // (in - pThresh).max(0.0)
      val pulse   = PulseDivider.ar(inTrig, pDiv)
      val timer   = Timer.ar(pulse) // .max(0.0001)
      val sd      = SampleDur.ir
      val freq    = timer.max(sd * 2).reciprocal * pMul
      val phSpeed = freq / SampleRate.ir
      val phasor  = Phasor.ar(trig = pulse, speed = phSpeed)
      val tr      = HPZ1.ar(phasor) < 0
      val pTime   = pAudio("time", ParamSpec(0.0 , 1.0), default(0.0))
      val sig     = DelayN.ar(tr /* pulse */, maxDelayTime = 1.0, delayTime = pTime) * pAmp

      mix(in, sig, pMix)
    }

    filterF("a~gate") { in =>
      val pThresh = pAudio("thresh", ParamSpec(0.01, 1  , ExpWarp), default(0.1))
      val pGate   = pAudio("gate"  , ParamSpec(0.0 , 1.0         ), default(0.0)) > pThresh
      val pLeak   = pAudio("leak"  , ParamSpec(0   , 1  , IntWarp), default(0.0)) > pThresh
      val pMix    = mkMix()
      val sig0    = Gate.ar(in, pGate)
      val leak    = LeakDC.ar(sig0)
      val sig     = Select.ar(pLeak, Seq(sig0, leak))
      mix(in, sig, pMix)
    }

    filterF("a~ff") { in =>
      shortcut = "L"
      val pLo     = pAudio("lo"  , ParamSpec(0.0, 1.0), default(0.0))
      val pHi     = pAudio("hi"  , ParamSpec(0.0, 1.0), default(1.0))
      val pMix    = mkMix()
      val inTrig  = in - 0.001 // pAudio("trig", ParamSpec(0.0, 1.0), default(0.0))

      val sig     = ToggleFF.ar(inTrig).linLin(0, 1, pLo, pHi)
      mix(in, sig, pMix)
    }

    filterF("a~trig") { in =>
      val pThresh = pAudio("thresh", ParamSpec(0.01, 1, ExpWarp), default(0.1))
      val pLo     = pAudio("lo"    , ParamSpec(0.0, 1.0), default(0.0))
      val pHi     = pAudio("hi"    , ParamSpec(0.0, 1.0), default(1.0))
      val inTrig  = in // pAudio("trig"  , ParamSpec(0.0, 1.0), default(0.0))
      val pDur    = pAudio("dur"   , ParamSpec(0.001, 1.0, ExpWarp), default(0.01))
      val pMix    = mkMix()

      val sig     = Trig1.ar(inTrig - pThresh, pDur).linLin(0, 1, pLo, pHi)
      mix(in, sig, pMix)
    }

    filterF("a~step") { in =>
      val pLo     = pAudio("lo"   , ParamSpec(0.0,  1.0       ), default(0.0))
      val pHi     = pAudio("hi"   , ParamSpec(0.0,  1.0       ), default(1.0))
      val pDiv    = pAudio("div"  , ParamSpec(1  , 16, IntWarp), default(1))
      val inTrig  = in - 0.001 // pAudio("trig" , ParamSpec(0.0,  1.0       ), default(0.0))
      val reset   = pAudio("reset", ParamSpec(0.0,  1.0       ), default(0.0))
      val pMix    = mkMix()
      val sig     = Stepper.ar(inTrig, reset = reset, lo = 0, hi = pDiv).linLin(0, pDiv, pLo, pHi)
      mix(in, sig, pMix)
    }

    if (sConfig.plugins) {
      filterF("squiz") { in =>
        val lowest  = 30.0
        val maxZero = 32
        val maxDur  = 1.0 / lowest * maxZero
        val pch     = pAudio("shift", ParamSpec(2, 16     , IntWarp), default(2f))
        val zero    = pAudio("zero" , ParamSpec(1, maxZero /*, IntWarp */), default(1)) // too many points, don't use Int
        val fade    = mkMix() // mkMix4()
        val wet     = Squiz.ar(in, pitchRatio = pch, zeroCrossings = zero, maxDur = maxDur)
        mix(in, wet, fade)
      }

      filterF("verb2") { in =>
        val inL     = if (genNumChannels <= 0) in out 0 else {
          ChannelRangeProxy(in, from = 0, until = genNumChannels, step = 2)
        }
        val inR     = if (genNumChannels <= 0) in out 1 else {
          ChannelRangeProxy(in, from = 1, until = genNumChannels, step = 2)
        }
        val time    = pAudio("time"  , ParamSpec(0.1, 60.0, ExpWarp), default(2f))
        val size    = pAudio("size"  , ParamSpec(0.5,  5.0, ExpWarp), default(1f))
        val diff    = pAudio("diff"  , ParamSpec(0,  1), default(0.707f))
        val damp    = pAudio("damp"  , ParamSpec(0,  1), default(0.1f))
        val tail    = 1.0f // pAudio("tail" , ParamSpec(-12, 12), default(0f)).dbamp
        val mod     = 0.1f
        val fade    = mkMix()
        val low     = tail// 1 - bright
        val high    = tail // bright
        val mid     = tail // 0.5
        val verb    = JPverb.ar(inL = inL, inR = inR, revTime = time, damp = damp, size = size, earlyDiff = diff,
        modDepth = mod, /* modFreq = ..., */ low = low, mid = mid, high = high)
        val verbL   = if (genNumChannels <= 0) verb out 0 else {
          ChannelRangeProxy(verb, from = 0, until = genNumChannels, step = 2)
        }
        val verbR   = if (genNumChannels <= 0) verb out 1 else {
          ChannelRangeProxy(verb, from = 1, until = genNumChannels, step = 2)
        }
        val wet     = Flatten(Zip(verbL, verbR))
        mix(in, wet, fade)
      }
    }

    // -------------- DIFFUSIONS --------------

    mainChansOption.foreach { mainChannels =>
      val numChans          = mainChannels.size
      val mainCfg           = NamedBusConfig("", 0 until numChans)
      val mainGroupsCfg     = mainCfg +: sConfig.mainGroups

      mainGroupsCfg.zipWithIndex.foreach { case (cfg, _ /* idx */) =>
        def placeChannels(sig: GE): GE = {
          if (cfg.numChannels == numChans) sig
          else {
            val flatSig = Flatten(sig)
            Flatten(
//              Seq(
//                Silent.ar(cfg.offset),
//                Flatten(sig),
//                Silent.ar(numChans - cfg.stopOffset)
//              )
              Seq.tabulate[GE](numChans) { ch =>
                val inIdx = cfg.indices.indexOf(ch)
                if (inIdx < 0)
                  DC.ar(0)
                else
                  flatSig out inIdx
              }
            )
          }
        }

        def mkAmp(): GE = {
          val db0 = pAudio("amp", ParamSpec(-inf, 20, DbFaderWarp), default(-inf))
          val db  = db0 - 10 * (db0 < -764)  // BUG IN SUPERCOLLIDER
          val res = db.dbAmp
          CheckBadValues.ar(res, id = 666)
          res
        }

        def mkOutAll(in: GE): GE = {
          val pAmp          = mkAmp()
          val sig           = in * Lag.ar(pAmp, 0.1) // .outputs
          val outChannels   = cfg.numChannels
          val outSig        = Util.wrapExtendChannels(outChannels, sig)
          placeChannels(outSig)
        }

        def mkOutPan(in: GE): GE = {
          val pSpread       = pControl("spr" , ParamSpec(0.0, 1.0),   default(0.25)) // XXX rand
          val pRota         = pControl("rota", ParamSpec(0.0, 1.0),   default(0.0))
          val pBase         = pControl("azi" , ParamSpec(0.0, 360.0), default(0.0))
          val pAmp          = mkAmp()

          val baseAzi       = Lag.kr(pBase, 0.5) + IRand(0, 360)
          val rotaAmt       = Lag.kr(pRota, 0.1)
          val spread        = Lag.kr(pSpread, 0.5)
          val outChannels   = cfg.numChannels
          val rotaSpeed     = 0.1
          val inSig0        = in * Lag.ar(pAmp, 0.1) // .outputs
          val inSig         = Mix(inSig0)
          val noise         = LFDNoise1.kr(rotaSpeed) * rotaAmt * 2
          val indicesIn     = ChannelIndices(in)
          val numChanIn     = NumChannels(in)
//          indicesIn.poll(0, "indices")
//          numChanIn.poll(0, "num-chans")
//          val pos0          = indicesIn * 2 / numChanIn
          val pos0          = indicesIn * 4 / numChanIn * pSpread
          // pos0.poll(0, "pos0")
          val pos1          = (baseAzi / 180) + pos0
          val pos           = pos1 + noise
          val level         = 1
          val width         = (spread * (outChannels - 2)) + 2
          val panAz         = PanAz.ar(outChannels, in = inSig, pos = pos, level = level, width = width, orient = 0)
          // tricky
          val outSig        = Mix(panAz)
          placeChannels(outSig)
        }

        def mkOutRnd(in: GE): GE = {
          val pAmp        = mkAmp()
          val pFreq       = pControl("freq", ParamSpec(0.01, 10, ExpWarp), default(0.1))
          val pPow        = pControl("pow" , ParamSpec(1, 10), default(2.0))
          val pLag        = pControl("lag" , ParamSpec(0.1, 10), default(1.0))

          val sig         = in * Lag.ar(pAmp, 0.1) // .outputs
//          NumChannels(sig).poll(0, "HELLO-IN")
          val outChannels = cfg.numChannels
          val sig1        = Util.wrapExtendChannels(outChannels, sig)
//          NumChannels(sig).poll(0, "HELLO-OUT")
          val freq        = pFreq
          val lag         = pLag
          val pw          = pPow
//          val rands       = Lag.ar(TRand.ar(0, 1, Dust.ar(List.fill(outChannels)(freq))).pow(pw), lag)
          val rands       = Lag.ar(TRand.ar(0, 1, Dust.ar(Util.wrapExtendChannels(outChannels, freq))).pow(pw), lag)
//          NumChannels(rands).poll(0, "HELLO-rands")
          val outSig      = sig1 * rands
//          NumChannels(outSig).poll(0, "HELLO-outSig")
          placeChannels(outSig)
        }

        if (nConfig.collector) {
          filterF(s"O-all${cfg.name}")(mkOutAll)
          filterF(s"O-pan${cfg.name}")(mkOutPan)
          filterF(s"O-rnd${cfg.name}")(mkOutRnd)
        } else {
          def mkDirectOut(sig0: GE): Unit = {
            val bad = CheckBadValues.ar(sig0)
            val sig = Gate.ar(sig0, bad sig_== 0)
            mainChannels.zipWithIndex.foreach { case (ch, i) =>
              val sig0 = sig out i
              val hpf  = sConfig.highPass
              val sig1 = if (hpf >= 16 && hpf < 20000) HPF.ar(sig0, hpf) else sig0
              Out.ar(ch, sig1)   // XXX TODO - should go to a bus w/ limiter
            }
          }

          collectorF(s"O-all${cfg.name}") { in =>
            val sig = mkOutAll(in)
            mkDirectOut(sig)
          }

          collectorF(s"O-pan${cfg.name}") { in =>
            val sig = mkOutPan(in)
            mkDirectOut(sig)
          }

          collectorF(s"O-rnd${cfg.name}") { in =>
            val sig = mkOutRnd(in)
            mkDirectOut(sig)
          }
        }
      }
    }

    collectorF("O-mute") { _ => DC.ar(0) }
  }
}