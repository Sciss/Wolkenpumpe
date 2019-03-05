/*
 *  BasicToggleButton.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2019 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */
package de.sciss.nuages

import java.awt.Color

import scala.swing.ToggleButton
import scala.swing.event.ButtonClicked

object BasicToggleButton {
  def apply(label: String)(action: Boolean => Unit) =
    new BasicToggleButton(label, action)
}

class BasicToggleButton private(label: String, action: Boolean => Unit) extends ToggleButton(label) { me =>
  peer.setUI(new SimpleToggleButtonUI)
  font = Wolkenpumpe.condensedFont.deriveFont(15f)
  background = Color.black
  foreground = Color.white

  listenTo(me)
  reactions += {
    case ButtonClicked(`me`) => action(selected)
  }
}