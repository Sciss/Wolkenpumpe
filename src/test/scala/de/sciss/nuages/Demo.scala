package de.sciss.nuages

import com.alee.laf.WebLookAndFeel
import de.sciss.lucre.stm.Cursor
import de.sciss.lucre.stm.store.BerkeleyDB
import de.sciss.lucre.synth.InMemory
import de.sciss.synth
import de.sciss.synth.Server
import de.sciss.synth.proc.{AuralSystem, Durable}

import scala.swing.SwingApplication

object Demo extends SwingApplication {
  val DEBUG = false

  def startup(args: Array[String]): Unit = {
    WebLookAndFeel.install()

    if (args.headOption.contains("--durable")) {
      type S = Durable
      val factory = BerkeleyDB.tmp()
      implicit val system = Durable(factory)
      val w = new Wolkenpumpe[S]
      w.run()
    } else {
      type S = InMemory
      implicit val system = InMemory()
      val w = new Wolkenpumpe[S] {
        /** Subclasses may want to override this. */
        override protected def registerProcesses(sCfg: ScissProcs.Config, nCfg: Nuages.Config)
                                                (implicit tx: S#Tx, cursor: Cursor[S], nuages: Nuages[S],
                                                 aural: AuralSystem): Unit = {
          super.registerProcesses(sCfg, nCfg)
          val dsl = new DSL[S]
          import dsl._

          generator("a~pulse") {
            import synth._
            import ugen._

            val pFreq   = pAudio("freq"     , ParamSpec(0.1 , 10000, ExpWarp), default = 15 /* 1 */)
            val pw      = pAudio("width"    , ParamSpec(0.0 ,     1.0),        default =  0.5)
            val pAmp    = pAudio("amp"      , ParamSpec(0.01,     1, ExpWarp), default =  0.1)
            val pFreqMix= pAudio("freq-src" , TrigSpec, default = 0)

            val inFreq  = pAudioIn("in-freq", 1, ParamSpec(0.1 , 10000, ExpWarp))

            val freq  = LinXFade2.ar(pFreq, inFreq, pFreqMix)
            val width = pw
            val sig   = Pulse.ar(freq, width)

            sig * pAmp
          }

          generator("a~DC") {
            val sig = pAudio("value", ParamSpec(0, 1), default = 0)
            sig
          }
        }

        /** Subclasses may want to override this. */
        override protected def configure(sCfg: ScissProcs.ConfigBuilder, nCfg: Nuages.ConfigBuilder,
                                         aCfg: Server.ConfigBuilder): Unit = {
          super.configure(sCfg, nCfg, aCfg)
          if (DEBUG) {
            sCfg.generatorChannels = 2
            sCfg.micInputs          = Vector(
              NamedBusConfig("m-dpa"  ,  2, 1),
              NamedBusConfig("m-at "  ,  0, 2)
            )
            sCfg.highPass = 100

            nCfg.masterChannels     = Some(2 to 43)
            nCfg.soloChannels       = Some(0 to 1)
            nCfg.recordPath         = Some("/tmp")

            aCfg.wireBuffers        = 512 // 1024
            aCfg.audioBuffers       = 4096
            aCfg.blockSize          = 128
          }
        }
      }
      w.run()
    }
  }
}
