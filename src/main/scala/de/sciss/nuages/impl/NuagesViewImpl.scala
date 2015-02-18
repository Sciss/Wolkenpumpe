/*
 *  NuagesViewImpl.scala
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
package impl

import java.awt.Color

import de.sciss.audiowidgets.TimelineModel
import de.sciss.lucre.stm
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.swing.{View, deferTx}
import de.sciss.lucre.synth.{Server, Sys, Txn}
import de.sciss.span.Span
import de.sciss.synth.proc.{Timeline, WorkspaceHandle, AuralSystem}
import de.sciss.synth.swing.j.JServerStatusPanel

import scala.swing.{BorderPanel, Component, GridBagPanel, Orientation, Swing}

object NuagesViewImpl {
  def apply[S <: Sys[S]](nuages: Nuages[S], config: Nuages.Config, numInputChannels: Int)
                        (implicit tx: S#Tx, aural: AuralSystem, workspace: WorkspaceHandle[S],
                         cursor: stm.Cursor[S]): NuagesView[S] = {
    val panel     = NuagesPanel(nuages, config)
    val tlm       = TimelineModel(Span(0L, (Timeline.SampleRate * 60 * 60 * 10).toLong), Timeline.SampleRate)
    val transport = panel.transport
    val trnspView = TransportViewImpl(transport, tlm)
    val res       = new Impl[S](panel, trnspView, numInputChannels = numInputChannels).init()
    res
  }

  private final class Impl[S <: Sys[S]](val panel: NuagesPanel[S], transportView: View[S], numInputChannels: Int)
                                       (implicit cursor: stm.Cursor[S])
    extends NuagesView[S] with ComponentHolder[Component] with AuralSystem.Client { impl =>

    import cursor.{step => atomic}
    import panel.config

    def init()(implicit tx: S#Tx): this.type = {
      deferTx(guiInit())
      panel.aural.serverOption.foreach(auralStarted)
      panel.aural.addClient(this)
      this
    }

    private var _controlPanel: ControlPanel       = _
    private var _serverPanel : JServerStatusPanel = _

    def controlPanel: ControlPanel = _controlPanel

    def auralStarted(s: Server)(implicit tx: Txn): Unit = deferTx(_serverPanel.server = Some(s.peer))
    def auralStopped()         (implicit tx: Txn): Unit = deferTx(_serverPanel.server = None        )

    private def guiInit(): Unit = {
      // val transition = new NuagesTransitionPanel(view)
      // val bottom = new BasicPanel(Orientation.Horizontal)

      val ggSouthBox = new BasicPanel(Orientation.Horizontal)
      // ggSouthBox.contents += bottom
      // ggSouthBox.contents += Swing.HStrut(8)
      val transportC = transportView.component
      transportC.border = Swing.EmptyBorder(0, 4, 0, 4)
      // transportC.background = Color.black
      ggSouthBox.contents += transportC
      ggSouthBox.contents += Swing.HStrut(8)
      _serverPanel = new JServerStatusPanel(JServerStatusPanel.COUNTS)
      ggSouthBox.contents += Component.wrap(_serverPanel)
      ggSouthBox.contents += Swing.HStrut(8)
      val cConfig = ControlPanel.Config()
      cConfig.numOutputChannels = panel.config.masterChannels.map(_.size).getOrElse(0)
      cConfig.numInputChannels  = numInputChannels
      cConfig.log = false
      _controlPanel = ControlPanel(cConfig)
      ggSouthBox.contents += controlPanel

      //ggSouthBox.contents += Button("DEBUG") {
      //  de.sciss.synth.Server.default ! osc.Message("/n_trace", 1003)
      //}

      ggSouthBox.contents += Swing.HGlue
      // currently not working:
      // ggSouthBox.contents += transition

      val ggFaderBox    = new GridBagPanel
      val gridCon       = new ggFaderBox.Constraints()
      gridCon.fill      = GridBagPanel.Fill.Both
      gridCon.weightx   = 1.0
      gridCon.gridwidth = 0

      def mkFader(ctrlSpecT: (ParamSpec, Double), weighty: Double)(fun: (Double, S#Tx) => Unit): Unit = {
        val (ctrlSpec, ctrlInit) = ctrlSpecT
        val slidSpec = ParamSpec(0, 0x10000)
        val slidInit = slidSpec.map(ctrlSpec.inverseMap(ctrlInit)).toInt
        val slid = BasicSlider(Orientation.Vertical, min = slidSpec.lo.toInt, max = slidSpec.hi.toInt,
          value = slidInit) { v =>
          val ctrlVal = ctrlSpec.map(slidSpec.inverseMap(v))
          //               grpMaster.set( ctrlName -> ctrlVal )
          atomic { implicit tx =>
            fun(ctrlVal, tx)
          }
        }

        gridCon.weighty = weighty
        ggFaderBox.layout(slid) = gridCon
      }

      if (config.masterChannels.isDefined) mkFader(NuagesPanel.masterAmpSpec, 0.75)(panel.setMasterVolume(_)(_))
      if (config.soloChannels  .isDefined) mkFader(NuagesPanel.soloAmpSpec  , 0.25)(panel.setSoloVolume  (_)(_))

      component = new BorderPanel {
        background = Color.black
        add(panel.component, BorderPanel.Position.Center)
        add(ggSouthBox     , BorderPanel.Position.South )
        add(ggFaderBox     , BorderPanel.Position.East  )
      }

      // if (config.fullScreenKey) installFullScreenKey(frame)
    }

    def dispose()(implicit tx: S#Tx): Unit = {
      panel.aural.removeClient(this)
      panel.dispose()
      // deferTx(_frame.dispose())
    }
  }
}