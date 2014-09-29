package de.sciss.nuages

import com.alee.laf.WebLookAndFeel
import de.sciss.lucre.stm.store.BerkeleyDB
import de.sciss.synth.proc.Durable

import scala.swing.SwingApplication

object Demo extends SwingApplication {
  def startup(args: Array[String]): Unit = {
    WebLookAndFeel.install()
    // Wolkenpumpe.main(args)

    type S = Durable
    val factory = BerkeleyDB.tmp()
    implicit val system = Durable(factory)
    Wolkenpumpe.run[S]()
  }
}
