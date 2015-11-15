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

import java.awt.event.{ActionEvent, InputEvent, KeyEvent}
import java.awt.{Color, Toolkit}
import javax.swing.{AbstractAction, JComponent, KeyStroke}

import de.sciss.audiowidgets.TimelineModel
import de.sciss.lucre.stm
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.swing.{View, defer, deferTx, requireEDT}
import de.sciss.lucre.synth.{Server, Synth, Sys, Txn}
import de.sciss.osc
import de.sciss.span.Span
import de.sciss.swingplus.PopupMenu
import de.sciss.synth.proc.{TimeRef, AuralSystem, WorkspaceHandle}
import de.sciss.synth.swing.j.JServerStatusPanel
import de.sciss.synth.{SynthGraph, addAfter, message}

import scala.collection.breakOut
import scala.swing.Swing._
import scala.swing.event.ButtonClicked
import scala.swing.{BoxPanel, Action, BorderPanel, Button, Component, GridBagPanel, Label, MenuItem, Orientation, TextField}

object NuagesViewImpl {
  def apply[S <: Sys[S]](nuages: Nuages[S], nuagesConfig: Nuages.Config, scissConfig: ScissProcs.Config)
                        (implicit tx: S#Tx, aural: AuralSystem, workspace: WorkspaceHandle[S],
                         cursor: stm.Cursor[S]): NuagesView[S] = {
    implicit val context: NuagesContext[S] = new NuagesContext[S] {}
    val panel     = NuagesPanel(nuages, nuagesConfig)
    val tlm       = TimelineModel(Span(0L, (TimeRef.SampleRate * 60 * 60 * 10).toLong), TimeRef.SampleRate)
    val transport = panel.transport
    val trnspView = TransportViewImpl(transport, tlm)
    val res       = new Impl[S](panel, trnspView, scissConfig).init()
    res
  }

  private final class Impl[S <: Sys[S]](val panel: NuagesPanel[S], transportView: View[S],
                                        sConfig: ScissProcs.Config)
                                       (implicit val cursor: stm.Cursor[S])
    extends NuagesView[S] with ComponentHolder[Component] with AuralSystem.Client { impl =>

    import panel.config

    private var _southBox: BoxPanel = _

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
      transportC.border = EmptyBorder(0, 4, 0, 4)
      // transportC.background = Color.black
      ggSouthBox.contents += transportC
      ggSouthBox.contents += HStrut(8)
      _serverPanel = new JServerStatusPanel(JServerStatusPanel.COUNTS)
      ggSouthBox.contents += Component.wrap(_serverPanel)
      ggSouthBox.contents += HStrut(8)
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

      ggSouthBox.contents += HStrut(4)
      val ggMenu = new Button("#")
      ggMenu.listenTo(ggMenu)
      ggMenu.reactions += {
        case ButtonClicked(_) => showMenu(ggMenu)
      }
      ggMenu.focusable = false
      ggSouthBox.contents += ggMenu

      ggSouthBox.contents += HGlue
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
          cursor.step { implicit tx =>
            fun(ctrlVal, tx)
          }
        }

        gridCon.weighty = weighty
        ggFaderBox.layout(slid) = gridCon
      }

      if (config.masterChannels.isDefined) mkFader(NuagesPanel.masterAmpSpec, 0.75)(panel.setMasterVolume(_)(_))
      if (config.soloChannels  .isDefined) mkFader(NuagesPanel.soloAmpSpec  , 0.25)(panel.setSoloVolume  (_)(_))

      _southBox = ggSouthBox

      component = new BorderPanel {
        background = Color.black
        add(panel.component, BorderPanel.Position.Center)
        add(ggSouthBox     , BorderPanel.Position.South )
        add(ggFaderBox     , BorderPanel.Position.East  )
      }

      // if (config.fullScreenKey) installFullScreenKey(frame)
    }

    def addSouthComponent(c: Component): Unit = {
      requireEDT()
      _southBox.contents += c
    }

    private def showMenu(parent: Component): Unit = {
      val selectedObjects = panel.selection.collect {
        case v: NuagesObj[S] => v
      }
      val pop = new PopupMenu {
        contents += new MenuItem(new Action("Save Macro...") {
          enabled = selectedObjects.nonEmpty
          def apply(): Unit = {
            val p = new OverlayPanel {
              val ggName = new TextField("Macro", 12)
              contents += new BasicPanel(Orientation.Horizontal) {
                contents += new Label("Name:") {
                  foreground = Color.white
                }
                contents += HStrut(4)
                contents += ggName
              }
              onComplete {
                close()
                panel.saveMacro(ggName.text, selectedObjects)
              }
            }
            panel.showOverlayPanel(p)
          }
        })
        contents += new MenuItem(new Action("Paste Macro...") {
          def apply(): Unit = panel.showInsertMacroDialog()
        })
      }
      pop.show(parent, 0, 0)
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
        import de.sciss.synth._
        import de.sciss.synth.ugen._
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
        val masterPeak    = Peak.kr(sigMast, meterTr)
        val masterRMS     = A2K.kr(Lag.ar(sigMast.squared, 0.1))
        val peak: GE      = Flatten(Seq(masterPeak, peoplePeak))
        val rms: GE       = Flatten(Seq(masterRMS, peopleRMS))
        val meterData     = Zip(peak, rms) // XXX correct?
        SendReply.kr(meterTr, meterData, "/meters")

        val amp = "amp".kr(1f)
        (masterBus zip sigMast0).foreach { case (ch, sig) =>
          ReplaceOut.ar(ch, Limiter.ar(sig * amp))
        }
      }
      val synPostM = Synth.play(dfPostM, Some("post-master"))(server.defaultGroup, addAction = addAfter)

      panel.masterSynth = Some(synPostM)

      val synPostMID = synPostM.peer.id
      val resp = message.Responder.add(server.peer) {
        case osc.Message( "/meters", `synPostMID`, 0, values @ _* ) =>
          defer {
            _controlPanel.meterUpdate(values.map(x => math.min(10f, x.asInstanceOf[Float]))(breakOut))
          }
      }
      scala.concurrent.stm.Txn.afterRollback(_ => resp.remove())(tx.peer)
    }
  }
}