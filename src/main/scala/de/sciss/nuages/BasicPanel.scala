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

import javax.swing.JPanel
import javax.swing.plaf.basic.BasicPanelUI
import java.awt.{Color, AWTEvent}

import scala.swing.{Swing, Orientation, BoxPanel}

class BasicPanel(orientation: Orientation.Value) extends BoxPanel(orientation) {
  def this() = this(Orientation.Vertical)

  peer.setUI(new BasicPanelUI())
  background  = Color.black
  foreground  = Color.white
}

class OverlayPanel(orientation: Orientation.Value) extends BasicPanel(orientation) {
  import AWTEvent._

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
}