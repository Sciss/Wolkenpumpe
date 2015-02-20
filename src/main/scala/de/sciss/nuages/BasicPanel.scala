/*
 *  BasicPanel.scala
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

import java.awt.{AWTEvent, Color}
import javax.swing.JPanel
import javax.swing.plaf.basic.BasicPanelUI

import de.sciss.desktop.{FocusType, KeyStrokes}
import de.sciss.swingplus.DoClickAction

import scala.swing.event.Key
import scala.swing.{BoxPanel, Button, Orientation, Swing}

class BasicPanel(orientation: Orientation.Value) extends BoxPanel(orientation) {
  def this() = this(Orientation.Vertical)

  peer.setUI(new BasicPanelUI())
  background  = Color.black
  foreground  = Color.white
}

object OverlayPanel {
}
class OverlayPanel(orientation: Orientation.Value) extends BasicPanel(orientation) {
  import java.awt.AWTEvent._

  def this() = this(Orientation.Vertical)

  override lazy val peer: JPanel with SuperMixin = {
    val p = new JPanel with SuperMixin {
      private val masks = MOUSE_EVENT_MASK | MOUSE_MOTION_EVENT_MASK | MOUSE_WHEEL_EVENT_MASK
      enableEvents(masks)

      override protected def processEvent(e: AWTEvent): Unit = {
        val id = e.getID
        if ((id & masks) == 0) {
          super.processEvent(e)
        }
      }
    }
    val l = new javax.swing.BoxLayout(p, orientation.id)
    p.setLayout(l)
    p
  }

  Swing.EmptyBorder(12, 12, 12, 12)

  def onComplete(action: => Unit): this.type = {
    val b = new BoxPanel(Orientation.Horizontal)
    val butCreate = /* Basic */ Button("Create")(action)
    butCreate.focusable = false
    b.contents += butCreate
    val strut = Swing.HStrut(8)
    // strut.background = Color.black
    b.contents += strut
    val butAbort = /* Basic */ Button("Abort")(close())
    import de.sciss.desktop.Implicits._
    butAbort.addAction("press", focus = FocusType.Window, action = new DoClickAction(butAbort) {
      accelerator = Some(KeyStrokes.plain + Key.Escape)
    })
    butAbort.focusable = false
    b.contents += butAbort

    contents += b
    pack()
  }

  def close(): Unit = peer.getParent.remove(peer)

  def pack(): this.type = {
    peer.setSize(preferredSize)
    peer.validate()
    this
  }
}