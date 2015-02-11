/*
 *  FrameImpl.scala
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
import java.awt.{Toolkit, Color}
import javax.swing.{AbstractAction, KeyStroke, JComponent}

import de.sciss.audiowidgets.TimelineModel
import de.sciss.lucre.stm
import de.sciss.lucre.synth.{Txn, Server, Sys}
import de.sciss.lucre.swing.{View, deferTx}
import de.sciss.osc
import de.sciss.span.Span
import de.sciss.swingplus.CloseOperation
import de.sciss.swingplus.Implicits._
import de.sciss.synth.proc.{AuralSystem, Timeline}
import de.sciss.synth.swing.j.JServerStatusPanel

import scala.swing.{Button, Component, GridBagPanel, BorderPanel, Orientation, Swing, Frame}
import Swing._

object FrameImpl {
  def apply[S <: Sys[S]](panel: NuagesPanel[S], numInputChannels: Int, undecorated: Boolean)
                        (implicit tx: S#Tx, cursor: stm.Cursor[S]): NuagesFrame[S] = {
    val tlm       = TimelineModel(Span(0L, (Timeline.SampleRate * 60 * 60 * 10).toLong), Timeline.SampleRate)
    val transport = panel.transport
    val trnspView = TransportViewImpl(transport, tlm)
    transport.play()
    new Impl(panel, trnspView, numInputChannels = numInputChannels, undecorated = undecorated).init()
  }

  private final class Impl[S <: Sys[S]](val view: NuagesPanel[S], transportView: View[S],
                                        numInputChannels: Int, undecorated: Boolean)
                                       (implicit cursor: stm.Cursor[S])
    extends NuagesFrame[S] with AuralSystem.Client { impl =>

    private var _frame: Frame = _
    def frame: Frame = {
      if (_frame == null) sys.error("Frame was not yet initialized")
      _frame
    }

    def frame_=(value: Frame): Unit = {
      if (_frame != null) sys.error("Frame was already initialized")
      _frame = value
    }

    import cursor.{step => atomic}
    import view.config

    def init()(implicit tx: S#Tx): this.type = {
      deferTx(guiInit())
      view.aural.serverOption.foreach(auralStarted)
      view.aural.addClient(this)
      this
    }

    private var _controlPanel: ControlPanel       = _
    private var _serverPanel : JServerStatusPanel = _

    def controlPanel: ControlPanel = _controlPanel

    def auralStarted(s: Server)(implicit tx: Txn): Unit = deferTx(_serverPanel.server = Some(s.peer))
    def auralStopped()         (implicit tx: Txn): Unit = deferTx(_serverPanel.server = None        )

    private def guiInit(): Unit = {
      val transition = new NuagesTransitionPanel(view)
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
      cConfig.numOutputChannels = view.config.masterChannels.map(_.size).getOrElse(0)
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

      if (config.masterChannels.isDefined) mkFader(NuagesPanel.masterAmpSpec, 0.75)(view.setMasterVolume(_)(_))
      if (config.soloChannels  .isDefined) mkFader(NuagesPanel.soloAmpSpec  , 0.25)(view.setSoloVolume  (_)(_))

      frame = new Frame {
        title = "Wolkenpumpe"
        peer.setUndecorated(impl.undecorated)

        contents = new BorderPanel {
          background = Color.black
          add(view.component, BorderPanel.Position.Center)
          add(ggSouthBox    , BorderPanel.Position.South )
          add(ggFaderBox    , BorderPanel.Position.East  )
        }

        this.defaultCloseOperation = CloseOperation.Exit
        size = (800, 600)
        centerOnScreen()
        open()
      }

      //      ProcTxn.atomic { implicit t =>
      //         ProcDemiurg.addListener( frame )
      //      }
      if (config.fullScreenKey) installFullScreenKey(frame)
      //      panel.display.requestFocus
    }

    private def installFullScreenKey(frame: Frame): Unit = {
      val d       = view.display
      val iMap    = d.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
      val aMap    = d.getActionMap
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

    def dispose()(implicit tx: S#Tx): Unit = {
      view.aural.removeClient(this)
      deferTx(_frame.dispose())
    }

    //    override def dispose(): Unit = {
    //      //      ProcTxn.atomic { implicit t => ProcDemiurg.removeListener( frame )}
    //      view.dispose()
    //      super.dispose()
    //    }

    //   private def defer( thunk: => Unit ) {
    //      EventQueue.invokeLater( new Runnable { def run() = thunk })
    //   }
  }
}
