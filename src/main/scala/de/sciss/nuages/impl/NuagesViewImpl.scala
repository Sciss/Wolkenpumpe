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

import java.awt.{Toolkit, Color}
import java.awt.event.{ActionEvent, InputEvent, KeyEvent}
import javax.swing.{AbstractAction, KeyStroke, JComponent}

import de.sciss.audiowidgets.TimelineModel
import de.sciss.lucre.stm
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.swing.{View, defer, deferTx}
import de.sciss.lucre.synth.{Synth, Server, Sys, Txn}
import de.sciss.span.Span
import de.sciss.{osc, synth}
import de.sciss.synth.{addAfter, SynthGraph}
import de.sciss.synth.message
import de.sciss.synth.proc.{Timeline, WorkspaceHandle, AuralSystem}
import de.sciss.synth.swing.j.JServerStatusPanel

import scala.collection.breakOut
import scala.swing.{BorderPanel, Component, GridBagPanel, Orientation, Swing}

object NuagesViewImpl {
  def apply[S <: Sys[S]](nuages: Nuages[S], nuagesConfig: Nuages.Config, scissConfig: ScissProcs.Config)
                        (implicit tx: S#Tx, aural: AuralSystem, workspace: WorkspaceHandle[S],
                         cursor: stm.Cursor[S]): NuagesView[S] = {
    val panel     = NuagesPanel(nuages, nuagesConfig)
    val tlm       = TimelineModel(Span(0L, (Timeline.SampleRate * 60 * 60 * 10).toLong), Timeline.SampleRate)
    val transport = panel.transport
    val trnspView = TransportViewImpl(transport, tlm)
    val res       = new Impl[S](panel, trnspView, scissConfig).init()
    res
  }

  private final class Impl[S <: Sys[S]](val panel: NuagesPanel[S], transportView: View[S],
                                        sConfig: ScissProcs.Config)
                                       (implicit val cursor: stm.Cursor[S])
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

    def auralStarted(s: Server)(implicit tx: Txn): Unit = {
      deferTx(_serverPanel.server = Some(s.peer))
      installMasterSynth(s)
    }

    def auralStopped()(implicit tx: Txn): Unit = deferTx {
      _serverPanel.server = None
    }

    def installFullScreenKey(frame: scala.swing.Window): Unit = {
      val display = panel.display
      val iMap    = display.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
      val aMap    = display.getActionMap
      val fsName  = "fullscreen"
      iMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit.getMenuShortcutKeyMask |
        InputEvent.SHIFT_MASK), fsName)
      aMap.put(fsName, new AbstractAction(fsName) {
        def actionPerformed(e: ActionEvent): Unit = {
          val gc = frame.peer.getGraphicsConfiguration
          val sd = gc.getDevice
          sd.setFullScreenWindow(if (sd.getFullScreenWindow == frame.peer) null else frame.peer)
        }
      })
    }


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
      val numInputChannels = sConfig.lineInputs.size + sConfig.micInputs.size
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
      val synth = panel.masterSynth
      panel.masterSynth = None
      panel.dispose()
      synth.foreach(_.dispose())
    }

    private def installMasterSynth(server: Server)
                                  (implicit tx: Txn): Unit = {
      val dfPostM = SynthGraph {
        import synth._; import ugen._
        // val masterBus = settings.frame.panel.masterBus.get // XXX ouch
        // val sigMast = In.ar( masterBus.index, masterBus.numChannels )
        import panel.{config => nConfig}
        val masterBus   = nConfig.masterChannels.getOrElse(Vector.empty)
        val sigMast0    = masterBus.map(ch => In.ar(ch))
        val sigMast: GE = sigMast0
        // external recorders
        sConfig.lineOutputs.foreach { cfg =>
          val off     = cfg.offset
          val numOut  = cfg.numChannels
          val numIn   = masterBus.size // numChannels
        val sig1: GE = if (numOut == numIn) {
            sigMast
          } else if (numIn == 1) {
            Seq.fill[GE](numOut)(sigMast)
          } else {
            val sigOut = SplayAz.ar(numOut, sigMast)
            Limiter.ar(sigOut, (-0.2).dbamp)
          }
          //            assert( sig1.numOutputs == numOut )
          Out.ar(off, sig1)
        }
        // master + people meters
        val meterTr    = Impulse.kr(20)
        val (peoplePeak, peopleRMS) = {
          val groups = /* if( NuagesApp.METER_MICS ) */ sConfig.micInputs ++ sConfig.lineInputs // else sConfig.lineInputs
          val res = groups.map { cfg =>
              val off        = cfg.offset
              val numIn      = cfg.numChannels
              val pSig       = In.ar(NumOutputBuses.ir + off, numIn)
              val peak       = Peak.kr(pSig, meterTr) // .outputs
            val peakM      = Reduce.max(peak)
              val rms        = A2K.kr(Lag.ar(pSig.squared, 0.1))
              val rmsM       = Mix.mono(rms) / numIn
              (peakM, rmsM)
            }
          (res.map( _._1 ): GE) -> (res.map( _._2 ): GE)  // elegant it's not
        }
        val masterPeak    = Peak.kr( sigMast, meterTr )
        val masterRMS     = A2K.kr( Lag.ar( sigMast.squared, 0.1 ))
        val peak: GE      = Flatten( Seq( masterPeak, peoplePeak ))
        val rms: GE       = Flatten( Seq( masterRMS, peopleRMS ))
        val meterData     = Zip( peak, rms )  // XXX correct?
        SendReply.kr( meterTr, meterData, "/meters" )

        val amp = "amp".kr(1f)
        (masterBus zip sigMast0).foreach { case (ch, sig) =>
          ReplaceOut.ar(ch, Limiter.ar(sig * amp))
        }
      }
      val synPostM = Synth.play(dfPostM, Some("post-master"))(server.defaultGroup, addAction = addAfter)

      panel.masterSynth = Some(synPostM)

      val synPostMID = synPostM.peer.id
      message.Responder.add(server.peer) {
        case osc.Message( "/meters", `synPostMID`, 0, values @ _* ) =>
          defer {
            _controlPanel.meterUpdate(values.map(_.asInstanceOf[Float])(breakOut))
          }
      }
    }
  }
}