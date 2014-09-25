package de.sciss.nuages
package impl

import java.awt.event.{ActionEvent, InputEvent, KeyEvent}
import java.awt.{Toolkit, GridBagConstraints, GridBagLayout, BorderLayout, Color}
import javax.swing.{AbstractAction, KeyStroke, JComponent, JPanel, Box, BoxLayout}

import de.sciss.lucre.synth.Sys
import de.sciss.lucre.swing.deferTx

import scala.swing.{Swing, Frame}
import Swing._

object FrameImpl {
  def apply[S <: Sys[S]](panel: NuagesPanel[S])(implicit tx: S#Tx): NuagesFrame[S] = {
    val res = new Impl(panel)
    deferTx(res.guiInit())
    res
  }

  private final class Impl[S <: Sys[S]](val view: NuagesPanel[S])
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

    // ---- constructor ----
    def guiInit(): Unit = {
      val transition = new NuagesTransitionPanel(view)
      val bottom = new BasicPanel
      bottom.setLayout(new BoxLayout(bottom, BoxLayout.X_AXIS))

      //      val ggEastBox     = new BasicPanel
      //      ggEastBox.setLayout( new BorderLayout() )
      //      val font          = Wolkenpumpe.condensedFont.deriveFont( 15f ) // WARNING: use float argument

      val ggSouthBox = Box.createHorizontalBox()
      ggSouthBox.add(bottom)
      ggSouthBox.add(Box.createHorizontalGlue())
      ggSouthBox.add(transition)

      val gridCon       = new GridBagConstraints()
      gridCon.fill      = GridBagConstraints.BOTH
      gridCon.weightx   = 1.0
      gridCon.gridwidth = GridBagConstraints.REMAINDER

      val gridLay = new GridBagLayout()
      val ggFaderBox = new JPanel(gridLay)
      //      ggEastBox.add( ggFaderBox, BorderLayout.EAST )
      //      ggEastBox.add( transition, BorderLayout.SOUTH )
      //      cp.add( BorderLayout.EAST, ggEastBox )

      //    def mkFader(ctrlSpecT: (ParamSpec, Double), weighty: Double)(fun: (Double, ProcTxn) => Unit): Unit = {
      //      val (ctrlSpec, ctrlInit) = ctrlSpecT
      //      val slidSpec = ParamSpec(0, 0x10000)
      //      val slidInit = slidSpec.map(ctrlSpec.unmap(ctrlInit)).toInt
      //      val slid = new JSlider(SwingConstants.VERTICAL, slidSpec.lo.toInt, slidSpec.hi.toInt, slidInit)
      //      slid.setUI(new BasicSliderUI(slid))
      //      slid.setBackground(Color.black)
      //      slid.setForeground(Color.white)
      //      slid.addChangeListener(new ChangeListener {
      //        def stateChanged(e: ChangeEvent): Unit = {
      //          val ctrlVal = ctrlSpec.map(slidSpec.unmap(slid.getValue))
      //          //               grpMaster.set( ctrlName -> ctrlVal )
      //          ProcTxn.atomic { t => fun(ctrlVal, t)}
      //        }
      //      })
      //      gridCon.weighty = weighty
      //      gridLay.setConstraints(slid, gridCon)
      //      ggFaderBox.add(slid)
      //    }

      //    if (config.masterChannels.isDefined) mkFader(NuagesPanel.masterAmpSpec, 0.75)(panel.setMasterVolume(_)(_))
      //    if (config.soloChannels  .isDefined) mkFader(NuagesPanel.soloAmpSpec  , 0.25)(panel.setSoloVolume  (_)(_))

      frame = new Frame {
        title = "Wolkenpumpe"
        // setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
        private val cp = peer.getContentPane
        cp.setBackground(Color.black)
        cp.add(view.component.peer, BorderLayout.CENTER)

        cp.add(ggSouthBox, BorderLayout.SOUTH)
        cp.add(ggFaderBox, BorderLayout.EAST)

        size = (640, 480)
        centerOnScreen()
        open()
      }

      //      ProcTxn.atomic { implicit t =>
      //         ProcDemiurg.addListener( frame )
      //      }
      if (view.config.fullScreenKey) installFullScreenKey(frame)
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
