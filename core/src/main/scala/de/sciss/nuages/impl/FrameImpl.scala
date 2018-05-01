/*
 *  FrameImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2017 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.lucre.swing.deferTx
import de.sciss.lucre.synth.Sys
import de.sciss.swingplus.CloseOperation
import de.sciss.swingplus.Implicits._

import scala.swing.Frame
import scala.swing.Swing._

object FrameImpl {
  def apply[S <: Sys[S]](view: NuagesView[S], undecorated: Boolean)
                        (implicit tx: S#Tx): NuagesFrame[S] = {
    val transport = view.panel.transport
    transport.play()
    new Impl[S](view, undecorated = undecorated).init()
  }

  private final class Impl[S <: Sys[S]](val view: NuagesView[S], undecorated: Boolean)
    extends NuagesFrame[S] { impl =>

    private var _frame: Frame = _
    def frame: Frame = {
      if (_frame == null) sys.error("Frame was not yet initialized")
      _frame
    }

    def frame_=(value: Frame): Unit = {
      if (_frame != null) sys.error("Frame was already initialized")
      _frame = value
    }

    def init()(implicit tx: S#Tx): this.type = {
      deferTx(guiInit())
      this
    }

    private def guiInit(): Unit = {
      frame = new Frame {
        title = "Wolkenpumpe"
        peer.setUndecorated(impl.undecorated)

        contents = view.component
        this.defaultCloseOperation = CloseOperation.Exit
        size = (800, 600)
        centerOnScreen()
        open()
      }

      val panel = view.panel
      if (panel.config.fullScreenKey) view.installFullScreenKey(frame)
    }

    def dispose()(implicit tx: S#Tx): Unit = {
      view.dispose()
      deferTx(_frame.dispose())
    }
  }
}
