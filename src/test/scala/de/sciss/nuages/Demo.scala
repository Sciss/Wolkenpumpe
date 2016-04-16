package de.sciss.nuages

import de.sciss.file._
import de.sciss.lucre.stm
import de.sciss.lucre.stm.store.BerkeleyDB
import de.sciss.lucre.synth.{InMemory, Sys}
import de.sciss.submin.Submin
import de.sciss.synth.Server
import de.sciss.synth.proc.Durable

object Demo {
  case class Config(durable: Option[File] = None, timeline: Boolean = false)

  val DEBUG = true

  def main(args: Array[String]): Unit = {
    val p = new scopt.OptionParser[Config]("Demo") {
      opt[File]('d', "durable")  text "Durable database"         action { case (f, c) => c.copy(durable  = Some(f)) }
      opt[Unit]('t', "timeline") text "Use performance timeline" action { case (_, c) => c.copy(timeline = true) }
    }
    p.parse(args, Config()).fold(sys.exit(1))(run)
  }

  private class DemoNuages[S <: Sys[S]] extends Wolkenpumpe[S] {
    /** Subclasses may want to override this. */
    override protected def configure(sCfg: ScissProcs.ConfigBuilder, nCfg: Nuages.ConfigBuilder,
                                     aCfg: Server.ConfigBuilder): Unit = {
      super.configure(sCfg, nCfg, aCfg)
      if (DEBUG) {
        sCfg.generatorChannels = 2
        sCfg.micInputs          = Vector.empty
        sCfg.lineInputs         = Vector.empty
        sCfg.lineOutputs        = Vector.empty

        nCfg.masterChannels     = Some(0 to 1)
        nCfg.soloChannels       = None
        nCfg.recordPath         = Some("/tmp")

        aCfg.wireBuffers        = 512
        aCfg.audioBuffers       = 4096
        aCfg.blockSize          = 128
      }
    }
  }

  private def mkNuages[S <: Sys[S]](nuagesH: stm.Source[S#Tx, Nuages[S]])(implicit cursor: stm.Cursor[S]): Unit = {
    val w = new DemoNuages[S]
    w.run(nuagesH)
  }

  def run(config: Config): Unit = {
    Submin.install(true)
    Wolkenpumpe.init()

    config.durable match {
      case Some(f) =>
        type S = Durable
        val factory = if (f.path == "<TMP>") {
          BerkeleyDB.tmp()
        } else {
          BerkeleyDB.factory(f)
        }
        implicit val system = Durable(factory)
        val nuagesH = system.root { implicit tx =>
          if (config.timeline) Nuages.timeline[S] else Nuages.folder[S]
        }
        mkNuages(nuagesH)

    case None =>
      type S = InMemory
      implicit val system = InMemory()

      val nuagesH = system.step { implicit tx =>
        val nuages = if (config.timeline) Nuages.timeline[S] else Nuages.folder[S]
        tx.newHandle(nuages)
      }
      mkNuages(nuagesH)
    }
  }
}
