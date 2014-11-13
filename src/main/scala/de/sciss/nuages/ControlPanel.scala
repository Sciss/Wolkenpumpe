package de.sciss.nuages

import java.awt.Dimension

import de.sciss.audiowidgets.PeakMeter
import de.sciss.desktop.LogPane

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.language.implicitConversions
import scala.swing.{Swing, Orientation}
import Swing._

object ControlPanel {

  sealed trait ConfigLike {
    def numOutputChannels: Int
    def numInputChannels : Int

    def log: Boolean
  }

  object Config {
    implicit def build(b: ConfigBuilder): Config = b.build
    def apply(): ConfigBuilder = new ConfigBuilder
  }

  sealed trait Config extends ConfigLike
  
  object ConfigBuilder {
    def apply(s: ConfigLike): ConfigBuilder = {
      val b = Config()
      b.numOutputChannels = s.numOutputChannels
      b.numInputChannels  = s.numInputChannels
      b.log               = s.log
      b
    }
  }

  final class ConfigBuilder private[ControlPanel]() extends ConfigLike {
    var numOutputChannels   = 2
    var numInputChannels    = 0
    var log                 = true

    def build: Config = ConfigImpl(numOutputChannels, numInputChannels, log)
  }

  private final case class ConfigImpl( numOutputChannels: Int, numInputChannels: Int, log: Boolean)
    extends Config

  def apply(config: Config = Config().build): ControlPanel = new ControlPanel(config)
}

class ControlPanel private(val config: ControlPanel.Config)
  extends BasicPanel(Orientation.Horizontal) {
  panel =>

  private def makeMeter(numChannels: Int): Option[PeakMeter] = if (numChannels > 0) {
    val p           = new PeakMeter()
    p.orientation   = Orientation.Horizontal
    p.numChannels   = numChannels
    p.borderVisible = true
    val d           = p.preferredSize
    val dn          = 30 / numChannels
    d.height        = numChannels * dn + 7
    p.maximumSize   = d
    p.preferredSize = d
    Some(p)
  } else None

  private val outMeterPanel  = makeMeter( config.numOutputChannels )
  private val inMeterPanel   = makeMeter( config.numInputChannels  )
  private val inDataOffset   = config.numOutputChannels << 1

  private def space(width: Int = 8): Unit = panel.contents += HStrut(width)

  private val logPane = if (config.log) {
    val p     = LogPane(columns = 30, rows = 2)
    val d     = outMeterPanel.orElse(inMeterPanel).map(_.preferredSize).getOrElse(new Dimension(0, 36))
    val d1    = p.component.preferredSize // getPreferredSize
    d1.height = d.height
    p.component.preferredSize = d1
    Some(p)
  } else None

  // ---- constructor ----

  outMeterPanel.foreach(panel.contents += _)
  inMeterPanel .foreach(panel.contents += _)

  logPane.foreach { p =>
    space()
    panel.contents += p.component
  }

  def meterUpdate(peakRMSPairs: Vec[Float]): Unit = {
    val tim = System.currentTimeMillis
    outMeterPanel.foreach(_.update(peakRMSPairs, 0           , tim))
    inMeterPanel .foreach(_.update(peakRMSPairs, inDataOffset, tim))
  }
}