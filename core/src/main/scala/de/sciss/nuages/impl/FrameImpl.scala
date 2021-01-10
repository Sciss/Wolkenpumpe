/*
 *  FrameImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2021 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.lucre.swing.LucreSwing.deferTx
import de.sciss.lucre.synth.Txn
import de.sciss.swingplus.CloseOperation
import de.sciss.swingplus.Implicits._

import scala.swing.Frame

object FrameImpl {
  def apply[T <: Txn[T]](view: NuagesView[T], undecorated: Boolean)
                        (implicit tx: T): NuagesFrame[T] = {
//    val transport = view.panel.transport
//    if (view.panel.config.autoStart) transport.play()
    new Impl[T](view, undecorated = undecorated).init()
  }

  private final class Impl[T <: Txn[T]](val view: NuagesView[T], undecorated: Boolean)
    extends NuagesFrame[T] { impl =>

    private var _frame: Frame = _
    def frame: Frame = {
      if (_frame == null) sys.error("Frame was not yet initialized")
      _frame
    }

    def frame_=(value: Frame): Unit = {
      if (_frame != null) sys.error("Frame was already initialized")
      _frame = value
    }

    def init()(implicit tx: T): this.type = {
      deferTx(guiInit())
      this
    }

    private def guiInit(): Unit = {
      frame = new Frame {
        title = "Wolkenpumpe"
        peer.setUndecorated(impl.undecorated)

        contents = view.component
        this.defaultCloseOperation = CloseOperation.Exit
//        size = (800, 600)
        pack()
        centerOnScreen()
        open()
      }

      val panel = view.panel
      if (panel.config.fullScreenKey) view.installFullScreenKey(frame)
    }

    def dispose()(implicit tx: T): Unit = {
      view.dispose()
      deferTx(_frame.dispose())
    }
  }
}
