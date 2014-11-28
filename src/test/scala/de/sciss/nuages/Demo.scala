package de.sciss.nuages

import com.alee.laf.WebLookAndFeel
import de.sciss.lucre.stm.store.BerkeleyDB
import de.sciss.lucre.synth.InMemory
import de.sciss.synth.Server
import de.sciss.synth.proc.Durable

import scala.swing.SwingApplication

object Demo extends SwingApplication {
  val DEBUG = false

  def startup(args: Array[String]): Unit = {
    WebLookAndFeel.install()

    if (args.headOption == Some("--durable")) {
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
