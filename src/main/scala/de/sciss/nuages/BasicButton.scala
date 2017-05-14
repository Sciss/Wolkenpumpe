/*
 *  BasicButton.scala
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

import java.awt.Color
import javax.swing.plaf.basic.BasicButtonUI

import scala.swing.Button
import scala.swing.event.ButtonClicked

object BasicButton {
  def apply(label: String)(action: => Unit): BasicButton = new BasicButton(label, action)
}

class BasicButton private(label: String, action: => Unit) extends Button(label) { me =>
  peer.setUI(new BasicButtonUI())
  background  = Color.black
  foreground  = Color.white

  focusable   = false
  font        = Wolkenpumpe.condensedFont.deriveFont(15f)

  listenTo(me)
  reactions += {
    case ButtonClicked(`me`) => action
  }
}