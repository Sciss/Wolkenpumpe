/*
 *  NuagesTransitionPanel.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2013 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either
 *  version 2, june 1991 of the License, or (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public
 *  License (gpl.txt) along with this software; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import java.awt.Color
import javax.swing.ButtonModel
import javax.swing.plaf.basic.BasicSliderUI
import de.sciss.lucre.synth.Sys
import scala.swing.event.ValueChanged
import scala.swing.{AbstractButton, Slider, ButtonGroup, Orientation}

class NuagesTransitionPanel[S <: Sys[S]](main: NuagesPanel[S]) extends BasicPanel(Orientation.Horizontal) {
  panel =>

  private val bg          = new ButtonGroup()
  private var models: Array[ButtonModel] = _
  private val specSlider  = ParamSpec(0, 0x10000)
  private val specTime    = ParamSpec(0.06, 60.0, ExpWarp)

  private val ggTypeInstant = BasicToggleButton("In")(if (_) dispatchTransition(inst = true , gl = false, xf = false))
  private val ggTypeGlide   = BasicToggleButton("Gl")(if (_) dispatchTransition(inst = false, gl = true , xf = false))
  private val ggTypeXFade   = BasicToggleButton("X" )(if (_) dispatchTransition(inst = false, gl = false, xf = true ))

  private val ggSlider    = BasicSlider(Orientation.Horizontal, min = specSlider.lo.toInt, max = specSlider.hi.toInt,
    value = specSlider.lo.toInt) { _ =>

    if (!ggTypeInstant.selected) {
      dispatchTransition(ggTypeInstant.selected, ggTypeGlide.selected, ggTypeXFade.selected)
    }
  }

  private def addButton(b: AbstractButton): Unit = {
    bg.buttons += b
    contents += b
  }

  private def dispatchTransition(inst: Boolean, gl: Boolean, xf: Boolean): Unit = {
    //      main.transition = if (inst) {
    //        (_) => Instant
    //      } else {
    //        val fdt = specTime.map(specSlider.unmap(ggSlider.getValue()))
    //        if (xf) {
    //          XFade(_, fdt)
    //        } else {
    //          Glide(_, fdt)
    //        }
    //      }
  }

  // ---- constructor ----
  addButton(ggTypeInstant)
  addButton(ggTypeGlide)
  addButton(ggTypeXFade)
//    models = new JavaConversions.JEnumerationWrapper(bg.getElements).toArray.map(_.getModel)
    //      panel.setLayout( new BoxLayout( panel, BoxLayout.Y_AXIS ))
    //      panel.add( box )
  contents += ggSlider

    //      val actionListener = new ActionListener {
    //         def actionPerformed( e: ActionEvent ) {
    //            dispatchTransition()
    //         }
    //      }
    //      ggTypeInstant.addActionListener( actionListener )
    //      ggTypeGlide.addActionListener( actionListener )
    //      ggTypeXFade.addActionListener( actionListener )

  def setTransition(idx: Int, tNorm: Double): Unit = {
//    if (idx < 0 || idx > 2) return
//    bg.setSelected(models(idx), true)
//    if (idx == 0) {
//      main.transition = (_) => Instant
//    } else {
//      val tNormC = math.max(0.0, math.min(1.0, tNorm))
//      val fdt = specTime.map(tNormC)
//      ggSlider.setValue(specSlider.map(tNormC).toInt)
//      main.transition = if (idx == 1) Glide(_, fdt) else XFade(_, fdt)
//    }
  }
}