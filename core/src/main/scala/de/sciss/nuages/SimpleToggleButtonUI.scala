/*
 *  SimpleToggleButtonUI.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2019 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import javax.swing.plaf.basic.BasicToggleButtonUI
import javax.swing.AbstractButton
import java.awt.{Color, Rectangle, Graphics}

class SimpleToggleButtonUI extends BasicToggleButtonUI {
  override protected def paintButtonPressed(g: Graphics, b: AbstractButton): Unit = {
    val model = b.getModel
    val color = if (model.isEnabled) {
      b.getForeground
    } else {
      mix(b.getForeground, Color.gray, 0.75f)
    }
    g.setColor(color)
    val in = b.getInsets
    g.fillRect(in.left, in.top, b.getWidth - (in.left + in.right), b.getHeight - (in.top + in.bottom))
  }

  override protected def paintText(g: Graphics, b: AbstractButton, textRect: Rectangle, text: String): Unit = {
    val model   = b.getModel
    val fm      = g.getFontMetrics
    val colorFg = if (model.isArmed && model.isPressed || model.isSelected) {
      b.getBackground
    } else {
      b.getForeground
    }
    g.setColor(colorFg)
    g.drawString(text, textRect.x, textRect.y + fm.getAscent)
  }

  private def mix(c1: Color, c2: Color, w: Float) = {
    val w2 = 1f - w
    new Color(((c1.getRed * w + c2.getRed * w2) + 0.5f).toInt,
      ((c1.getGreen * w + c2.getGreen * w2) + 0.5f).toInt,
      ((c1.getBlue * w + c2.getBlue * w2) + 0.5f).toInt)
  }

  override protected def paintFocus(g: Graphics, b: AbstractButton,
                                    viewRect: Rectangle, textRect: Rectangle, iconRect: Rectangle): Unit = ()
}