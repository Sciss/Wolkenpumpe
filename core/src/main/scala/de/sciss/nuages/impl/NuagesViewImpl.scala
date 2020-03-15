/*
 *  NuagesViewImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2020 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import java.awt.event.{ActionEvent, InputEvent, KeyEvent}
import java.awt.{Color, Toolkit}

import de.sciss.audiowidgets.{RotaryKnob, TimelineModel}
import de.sciss.lucre.stm
import de.sciss.lucre.swing.LucreSwing.{defer, deferTx, requireEDT}
import de.sciss.lucre.swing.View
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.synth.{Server, Synth, Sys, Txn}
import de.sciss.osc
import de.sciss.span.Span
import de.sciss.synth.UGenSource.Vec
import de.sciss.synth.proc.gui.TransportView
import de.sciss.synth.proc.{AuralSystem, TimeRef, Universe}
import de.sciss.synth.swing.j.JServerStatusPanel
import de.sciss.synth.{SynthGraph, addAfter, message}
import javax.swing.{AbstractAction, JComponent, KeyStroke, SwingUtilities}

import scala.swing.Swing._
import scala.swing.{BorderPanel, BoxPanel, Component, GridBagPanel, Orientation}

object NuagesViewImpl {
  def apply[S <: Sys[S]](nuages: Nuages[S], nuagesConfig: Nuages.Config)
                        (implicit tx: S#Tx, universe: Universe[S]): NuagesView[S] = {
    implicit val context: NuagesContext[S] = NuagesContext[S]
    val panel       = NuagesPanel(nuages, nuagesConfig)
    val visSpan     = Span(0L, TimeRef.SampleRate.toLong)  // not used
    val virtualSpan = Span(0L, (60.0 * 60.0 * TimeRef.SampleRate).toLong)  // not used
    val tlm         = TimelineModel(bounds = Span.from(0L), visible = visSpan, virtual = virtualSpan,
      clipStop = false, sampleRate = TimeRef.SampleRate)
    val transport   = panel.transport
    import universe.cursor
    val trnspView   = TransportView(transport, tlm, hasMillis = false, hasLoop = false, hasShortcuts = false)
    val res         = new Impl[S](panel, trnspView).init()
    res
  }

  private final class Impl[S <: Sys[S]](val panel: NuagesPanel[S], transportView: View[S])
                                       (implicit val cursor: stm.Cursor[S])
    extends NuagesView[S] with ComponentHolder[Component] with AuralSystem.Client { impl =>

    type C = Component

    import panel.{config => nConfig}

    private[this] var _southBox     : BoxPanel            = _
    private[this] var _controlPanel : ControlPanel        = _
    private[this] var _serverPanel  : JServerStatusPanel  = _

    def init()(implicit tx: S#Tx): this.type = {
      deferTx(guiInit())
      val aural = panel.universe.auralSystem
      aural.serverOption.foreach(auralStarted)
      aural.addClient(this)
      this
    }


    def controlPanel: ControlPanel = _controlPanel

    def auralStarted(s: Server)(implicit tx: Txn): Unit = {
      deferTx(_serverPanel.server = Some(s.peer))
      installMasterSynth(s)
    }

    def auralStopped()(implicit tx: Txn): Unit = deferTx {
      _serverPanel.server = None
    }

    override def installFullScreenKey(frame: scala.swing.RootPanel): Unit = {
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
          val w  = SwingUtilities.getWindowAncestor(frame.peer.getRootPane)
          sd.setFullScreenWindow(if (sd.getFullScreenWindow == w) null else w)
        }
      })

      val treeName = "dump-tree"
      iMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_T, Toolkit.getDefaultToolkit.getMenuShortcutKeyMask |
        InputEvent.SHIFT_MASK), treeName)
      aMap.put(treeName, new AbstractAction(treeName) {
        def actionPerformed(e: ActionEvent): Unit = {
          _serverPanel.server.foreach(_.dumpTree(controls = true))
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
      val numInputChannels = nConfig.lineInputs.size + nConfig.micInputs.size
      cConfig.numInputChannels  = numInputChannels
      cConfig.log = false
      _controlPanel = ControlPanel(cConfig)
      ggSouthBox.contents += controlPanel

      //ggSouthBox.contents += Button("DEBUG") {
      //  de.sciss.synth.Server.default ! osc.Message("/n_trace", 1003)
      //}

//      ggSouthBox.contents += HStrut(4)
//
      // XXX TODO --- macros are half broken now?
//      val ggMenu = new Button("#")
//      ggMenu.listenTo(ggMenu)
//      ggMenu.reactions += {
//        case ButtonClicked(_) => showMenu(ggMenu)
//      }
//      ggMenu.focusable = false
//      ggSouthBox.contents += ggMenu

      val ggGlideTime = new RotaryKnob(panel.glideTimeModel)
      ggGlideTime.maximumSize = ggGlideTime.preferredSize
      ggSouthBox.contents += HGlue
      ggSouthBox.contents += ggGlideTime

//      ggSouthBox.contents += HGlue
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

      if (nConfig.masterChannels.isDefined) mkFader(NuagesPanel.masterAmpSpec, 0.75)(panel.setMasterVolume(_)(_))
      if (nConfig.soloChannels  .isDefined) mkFader(NuagesPanel.soloAmpSpec  , 0.25)(panel.setSoloVolume  (_)(_))

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

//    private def showMenu(parent: Component): Unit = {
//      val selectedObjects = panel.selection.collect {
//        case v: NuagesObj[S] => v
//      }
//      val pop = new PopupMenu {
//        contents += new MenuItem(new Action("Save Macro...") {
//          enabled = selectedObjects.nonEmpty
//          def apply(): Unit = {
//            val p = new OverlayPanel {
//              val ggName = new TextField("Macro", 12)
//              contents += new BasicPanel(Orientation.Horizontal) {
//                contents += new Label("Name:") {
//                  foreground = Color.white
//                }
//                contents += HStrut(4)
//                contents += ggName
//              }
//              onComplete {
//                close()
//                panel.saveMacro(ggName.text, selectedObjects)
//              }
//            }
//            panel.showOverlayPanel(p)
//          }
//        })
//        contents += new MenuItem(new Action("Paste Macro...") {
//          def apply(): Unit = panel.showInsertMacroDialog()
//        })
//      }
//      pop.show(parent, 0, 0)
//    }

    def dispose()(implicit tx: S#Tx): Unit = {
      val aural = panel.universe.auralSystem
      aural.removeClient(this)
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
        val masterBus   = nConfig.masterChannels.getOrElse(Vector.empty)
        val sigMast0    = masterBus.map(ch => In.ar(ch))
        val sigMast: GE = sigMast0
        // external recorders
        nConfig.lineOutputs.foreach { cfg =>
//          val off     = cfg.offset
          val numOut  = cfg.numChannels
          val numIn   = masterBus.size // numChannels
          val sig1: GE = if (numOut == numIn) {
            sigMast
          } else if (numIn == 1) {
            Seq.fill[GE](numOut)(sigMast)
          } else {
            val sigOut = SplayAz.ar(numOut, sigMast)
            Limiter.ar(sigOut, (-0.2).dbAmp)
          }
          //            assert( sig1.numOutputs == numOut )
          // Out.ar(off, sig1)
          PhysicalOut.ar(cfg.indices, sig1)
        }
        // master + people meters
        val meterTr    = Impulse.kr(20)
        val (peoplePeak, peopleRMS) = {
          val groups = /* if( NuagesApp.METER_MICS ) */ nConfig.micInputs ++ nConfig.lineInputs // else sConfig.lineInputs
          val res = groups.map { cfg =>
//              val off        = cfg.offset
              val numIn      = cfg.numChannels
//              val pSig       = In.ar(NumOutputBuses.ir + off, numIn)
              val pSig       = PhysicalIn.ar(cfg.indices)
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

        import Ops._
        val amp = "amp".kr(1f)
        (masterBus zip sigMast0).foreach { case (ch, sig) =>
          ReplaceOut.ar(ch, Limiter.ar(sig * amp))
        }
      }
      val synPostM = Synth.play(dfPostM, Some("post-master"))(server.defaultGroup, addAction = addAfter)

      panel.masterSynth = Some(synPostM)

      val synPostMId = synPostM.peer.id
      val resp = message.Responder.add(server.peer) {
        case osc.Message( "/meters", `synPostMId`, 0, values @ _* ) =>
          val vec: Vec[Float] = values match {
            case vv: Vec[_] => vv             .map(v => Math.min(10f, v.asInstanceOf[Float]))
            case _          => values.iterator.map(v => Math.min(10f, v.asInstanceOf[Float])).toIndexedSeq
          }
          defer {
            _controlPanel.meterUpdate(vec)
          }
      }
      scala.concurrent.stm.Txn.afterRollback(_ => resp.remove())(tx.peer)
    }
  }
}