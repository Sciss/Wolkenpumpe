/*
 *  BasicSlider.scala
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
import javax.swing.plaf.basic.BasicSliderUI

import scala.swing.event.ValueChanged
import scala.swing.{Slider, Orientation}

object BasicSlider {
  def apply(orientation: Orientation.Value, min: Int, max: Int, value: Int)(action: Int => Unit): BasicSlider = {
    val res = new BasicSlider(action)
    res.orientation = orientation
    res.min         = min
    res.max         = max
    res.value       = value
    res
  }
}
class BasicSlider private(action: Int => Unit)
  extends Slider { me =>

  peer.setUI(new BasicSliderUI(peer))
  background  = Color.black
  foreground  = Color.white

  listenTo(me)
  reactions += {
    case ValueChanged(`me`) => action(value)
  }
}
