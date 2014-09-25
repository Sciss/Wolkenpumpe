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
