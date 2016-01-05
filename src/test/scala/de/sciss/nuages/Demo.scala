package de.sciss.nuages

import com.alee.laf.WebLookAndFeel
import de.sciss.file.File
import de.sciss.lucre.stm.store.BerkeleyDB
import de.sciss.lucre.synth.InMemory
import de.sciss.synth.Server
import de.sciss.synth.proc.Durable

object Demo {
  case class Config(durable: Option[File] = None, timeline: Boolean = false)

  val DEBUG = true

  def main(args: Array[String]): Unit = {
    val p = new scopt.OptionParser[Config]("Demo") {
      opt[File]('d', "durable") text "Durable database" action { case (f, c) => c.copy(durable = Some(f)) }
      opt[Unit]('t', "timeline") text "Use performance timeline" action { case (_, c) => c.copy(timeline = true) }
    }
    p.parse(args, Config()).fold(sys.exit(1))(run)
  }

  def run(config: Config): Unit = {
    WebLookAndFeel.install()
    Wolkenpumpe.init()

    config.durable match {
      case Some(f) =>
        type S = Durable
        val factory = BerkeleyDB.factory(f) // .tmp()
        implicit val system = Durable(factory)
        val w = new Wolkenpumpe[S]
        val nuagesH = system.root { implicit tx =>
          if (config.timeline) Nuages.timeline[S] else Nuages.folder[S]
        }
        w.run(nuagesH)

    case None =>
      type S = InMemory
      implicit val system = InMemory()
      val w = new Wolkenpumpe[S] {
//        /** Subclasses may want to override this. */
//        override protected def registerProcesses(sCfg: ScissProcs.Config, nCfg: Nuages.Config, nFinder: NuagesFinder)
//                                                (implicit tx: S#Tx, cursor: Cursor[S], nuages: Nuages[S],
//                                                 aural: AuralSystem): Unit = {
//          super.registerProcesses(sCfg, nCfg, nFinder)
//          val dsl = new DSL[S]
//          import dsl._
//
//          generator("a~pulse") {
//            import synth._
//            import ugen._
//
//            val pFreq   = pAudio("freq"     , ParamSpec(0.1 , 10000, ExpWarp), default = 15.0 /* 1 */)
//            val pw      = pAudio("width"    , ParamSpec(0.0 ,     1.0),        default =  0.5)
//            val pAmp    = pAudio("amp"      , ParamSpec(0.01,     1, ExpWarp), default =  0.1)
//            val pFreqMix= pAudio("freq-src" , TrigSpec, default = 0.0)
//
//            val inFreq  = pAudioIn("in-freq", 1, ParamSpec(0.1 , 10000, ExpWarp))
//
//            val freq  = LinXFade2.ar(pFreq, inFreq, pFreqMix)
//            val width = pw
//            val sig   = Pulse.ar(freq, width)
//
//            sig * pAmp
//          }
//        }

        /** Subclasses may want to override this. */
        override protected def configure(sCfg: ScissProcs.ConfigBuilder, nCfg: Nuages.ConfigBuilder,
                                         aCfg: Server.ConfigBuilder): Unit = {
          super.configure(sCfg, nCfg, aCfg)
          if (DEBUG) {
            sCfg.generatorChannels = 2
            sCfg.micInputs          = Vector.empty
            sCfg.lineInputs         = Vector.empty
            sCfg.lineOutputs        = Vector.empty

//              NamedBusConfig("m-dpa"  ,  2, 1),
//              NamedBusConfig("m-at "  ,  0, 2)
            // sCfg.highPass = 100

            nCfg.masterChannels     = Some(0 to 1) // Some(2 to 43)
            nCfg.soloChannels       = None // Some(0 to 1)
            nCfg.recordPath         = Some("/tmp")

            aCfg.wireBuffers        = 512 // 1024
            aCfg.audioBuffers       = 4096
            aCfg.blockSize          = 128
          }
        }
      }

      // val nuagesH = system.step { implicit tx => tx.newHandle(Nuages.timeline[S]) }
      val nuagesH = system.step { implicit tx =>
        val nuages = if (config.timeline) Nuages.timeline[S] else Nuages.folder[S]
        tx.newHandle(nuages)
      }
      w.run(nuagesH)
    }
  }
}
