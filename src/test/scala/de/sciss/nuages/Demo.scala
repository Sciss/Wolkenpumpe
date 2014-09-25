package de.sciss.nuages

import com.alee.laf.WebLookAndFeel

import scala.swing.SwingApplication

object Demo extends SwingApplication {
  def startup(args: Array[String]): Unit = {
    WebLookAndFeel.install()
    Wolkenpumpe.main(args)
  }
}
