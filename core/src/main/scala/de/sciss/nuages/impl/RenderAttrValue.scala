/*
 *  RenderAttrValue.scala
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
package impl

import java.awt.{Shape, Graphics2D}
import java.awt.geom.{Arc2D, GeneralPath, Area}

import de.sciss.lucre.synth.Sys
import de.sciss.nuages.impl.NuagesDataImpl._
import prefuse.data.{Node => PNode}
import prefuse.util.ColorLib
import prefuse.visual.VisualItem

import scala.swing.Color

trait RenderAttrValue[S <: Sys[S]] extends NuagesDataImpl[S] {
  type A

  protected final var renderedValue: A        = null.asInstanceOf[A]
  protected final var renderedValid: Boolean  = false

  protected final val valueArea               = new Area()
  protected final val containerArea           = new Area()

  // ---- abstract ----

  protected def spec: ParamSpec

  protected def renderDrag(g: Graphics2D): Unit

  protected def renderValueUpdated(): Unit

  protected def valueColor: Color

//  @volatile
//  protected final var valueA: A = _

  @volatile
  protected def valueA: A

  // ---- impl ----

  protected final def damageReport(pNode: PNode): Unit = {
    val _vis = main.visualization
    val visItem = _vis.getVisualItem(NuagesPanel.GROUP_GRAPH, pNode)
    _vis.damageReport(visItem, visItem.getBounds)
  }

  private[this] val spikes: Shape = if (spec.warp == IntWarp) {
    val loInt = spec.map(0.0).toInt
    val hiInt = spec.map(1.0).toInt
    val sz    = hiInt - loInt
    if (sz > 1 && sz <= 33) { // at least one spike and ignore if too many
    val res = new GeneralPath
      var i = loInt + 1
      while (i < hiInt) {
        val v = spec.inverseMap(i)
        // println(s"spike($i) = $v")
        setSpine(v)
        res.append(gLine, false)
        i += 1
      }
      res
    } else null
  } else null

  final protected def renderValueDetail(g: Graphics2D, vi: VisualItem): Unit = {
    val v = valueA
    if (!renderedValid || renderedValue != v) {
      renderedValue = v
      renderValueUpdated()
      renderedValid = true
    }

    g.setColor(valueColor)
    g.fill(valueArea)
    renderDrag(g)
    g.setColor(ColorLib.getColor(vi.getStrokeColor))
    g.draw(gp)

    if (spikes != null) {
      val strkOrig = g.getStroke
      g.setStroke(strkDotted)
      g.draw(spikes)
      g.setStroke(strkOrig)
    }
  }

  // ---- utilities ----

  final protected def renderValueUpdated1(v: Double): Unit = {
    val vc        = math.max(0, math.min(1, v))
    val angExtent = (vc * 270).toInt
    val angStart  = 225 - angExtent
    // val pValArc   = new Arc2D.Double(0, 0, r.getWidth, r.getHeight, angStart, angExtent, Arc2D.PIE)
    gArc.setArc(0, 0, r.getWidth, r.getHeight, angStart, angExtent, Arc2D.PIE)
    valueArea.reset()
    valueArea.add(new Area(gArc))
    valueArea.subtract(new Area(innerShape))
  }

  final protected def valueText1(v: Double): String = {
    val m = spec.map(v)
    if (spec.warp == IntWarp) m.toInt.toString
    else {
      if (m == Double.PositiveInfinity) "Inf"
      else if (m == Double.NegativeInfinity) "-Inf"
      else if (java.lang.Double.isNaN(m)) "NaN"
      else new java.math.BigDecimal(m, threeDigits).toPlainString
    }
  }

  final protected def updateContainerArea(): Unit = {
    // val pContArc = new Arc2D.Double(0, 0, r.getWidth, r.getHeight, -45, 270, Arc2D.PIE)
    gArc.setArc(0, 0, r.getWidth, r.getHeight, -45, 270, Arc2D.PIE)
    containerArea.reset()
    containerArea.add(new Area(gArc))
    containerArea.subtract(new Area(innerShape))
    gp.append(containerArea, false)
    // renderedValue = invalidRenderedValue // Vector.empty // Double.NaN // triggers updateRenderValue
    renderedValid = false
  }
}
