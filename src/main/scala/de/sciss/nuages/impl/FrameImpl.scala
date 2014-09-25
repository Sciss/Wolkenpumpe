package de.sciss.nuages
package impl

import java.awt.event.{ActionEvent, InputEvent, KeyEvent}
import java.awt.{Toolkit, GridBagConstraints, GridBagLayout, BorderLayout, Color}
import javax.swing.event.{ChangeEvent, ChangeListener}
import javax.swing.plaf.basic.BasicSliderUI
import javax.swing.{SwingConstants, JSlider, AbstractAction, KeyStroke, JComponent, JPanel, Box, BoxLayout}

import de.sciss.lucre.stm
import de.sciss.lucre.synth.Sys
import de.sciss.lucre.swing.deferTx

import scala.swing.{Slider, GridBagPanel, BorderPanel, BoxPanel, Orientation, Swing, Frame}
import Swing._

object FrameImpl {
  def apply[S <: Sys[S]](panel: NuagesPanel[S])(implicit tx: S#Tx, cursor: stm.Cursor[S]): NuagesFrame[S] = {
    val res = new Impl(panel)
    deferTx(res.guiInit())
    res
  }

  private final class Impl[S <: Sys[S]](val view: NuagesPanel[S])(implicit cursor: stm.Cursor[S])
    extends NuagesFrame[S] {

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

    // ---- constructor ----
    def guiInit(): Unit = {
      val transition = new NuagesTransitionPanel(view)
      val bottom = new BasicPanel(Orientation.Horizontal)

      //      val ggEastBox     = new BasicPanel
      //      ggEastBox.setLayout( new BorderLayout() )
      //      val font          = Wolkenpumpe.condensedFont.deriveFont( 15f ) // WARNING: use float argument

      val ggSouthBox = new BoxPanel(Orientation.Horizontal)
      ggSouthBox.contents += bottom
      ggSouthBox.contents += Swing.HGlue
      ggSouthBox.contents += transition

      val ggFaderBox    = new GridBagPanel
      val gridCon       = new ggFaderBox.Constraints()
      gridCon.fill      = GridBagPanel.Fill.Both
      gridCon.weightx   = 1.0
      gridCon.gridwidth = 0

      def mkFader(ctrlSpecT: (ParamSpec, Double), weighty: Double)(fun: (Double, S#Tx) => Unit): Unit = {
        val (ctrlSpec, ctrlInit) = ctrlSpecT
        val slidSpec = ParamSpec(0, 0x10000)
        val slidInit = slidSpec.map(ctrlSpec.unmap(ctrlInit)).toInt
        val slid = BasicSlider(Orientation.Vertical, min = slidSpec.lo.toInt, max = slidSpec.hi.toInt,
          value = slidInit) { v =>
            val ctrlVal = ctrlSpec.map(slidSpec.unmap(v))
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
        // setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)

        contents = new BorderPanel {
          background = Color.black
          add(view.component, BorderPanel.Position.Center)
          add(ggSouthBox    , BorderPanel.Position.South )
          add(ggFaderBox    , BorderPanel.Position.East  )
        }

        size = (640, 480)
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
