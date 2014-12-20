/*
 *  VisualControlImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2014 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import java.awt.Graphics2D
import java.awt.event.MouseEvent
import java.awt.geom.{Arc2D, Point2D, Area}

import de.sciss.lucre.expr.{Expr, Double => DoubleEx}
import de.sciss.lucre.synth.{Synth, Sys}
import de.sciss.synth.proc.{Obj, Scan, DoubleElem}
import prefuse.util.ColorLib
import prefuse.visual.VisualItem

import scala.concurrent.stm.Ref

object VisualControlImpl {
  private val defaultSpec = ParamSpec()

  private def getSpec[S <: Sys[S]](parent: VisualObj[S], key: String)(implicit tx: S#Tx): ParamSpec =
    parent.objH().attr[ParamSpec.Elem](s"$key-${ParamSpec.Key}").map(_.value).getOrElse(defaultSpec)

  def scalar[S <: Sys[S]](parent: VisualObj[S], key: String,
                          dObj: DoubleElem.Obj[S])(implicit tx: S#Tx): VisualControl[S] = {
    val value = dObj.elem.peer.value
    val spec  = getSpec(parent, key)
    new VisualControlImpl(parent, key, spec, value, mapping = None)
  }

  def scan[S <: Sys[S]](parent: VisualObj[S], key: String,
                        sObj: Scan.Obj[S])(implicit tx: S#Tx): VisualControl[S] = {
    val value = 0.5 // XXX TODO
    val spec  = getSpec(parent, key)
    new VisualControlImpl(parent, key, spec, value, mapping = Some(new MappingImpl))
  }

  private final class Drag(val angStart: Double, val valueStart: Double, val instant: Boolean) {
    var dragValue = valueStart
  }

  private final class MappingImpl[S <: Sys[S]] extends VisualControl.Mapping[S] {
    val synth   = Ref(Option.empty[Synth])
    var source  = Option.empty[VisualScan[S]]
  }
}
final class VisualControlImpl[S <: Sys[S]] private(val parent: VisualObj[S], val key: String,
                                                   val /* var */ spec: ParamSpec, @volatile var value: Double,
                                                   val mapping: Option[VisualControl.Mapping[S]])
  extends VisualParamImpl[S] with VisualControl[S] {

  import VisualDataImpl._
  import VisualControlImpl.Drag

  private val mapped: Boolean = mapping.isDefined

  private var renderedValue = Double.NaN
  private val containerArea = new Area()
  private val valueArea     = new Area()

  private var drag: Option[Drag] = None

  //      private def isValid = vProc.isValid

  override def itemPressed(vi: VisualItem, e: MouseEvent, pt: Point2D): Boolean = {
    // if (!vProc.isAlive) return false
    if (mapped) return true

    //         if( super.itemPressed( vi, e, pt )) return true

    if (containerArea.contains(pt.getX - r.getX, pt.getY - r.getY)) {
      val dy = r.getCenterY - pt.getY
      val dx = pt.getX - r.getCenterX
      val ang = math.max(0.0, math.min(1.0, (((-math.atan2(dy, dx) / math.Pi + 3.5) % 2.0) - 0.25) / 1.5))
      val instant = true // !vProc.state.playing || vProc.state.bypassed || main.transition(0) == Instant
      val vStart = if (e.isAltDown) {
          //               val res = math.min( 1.0f, (((ang / math.Pi + 3.25) % 2.0) / 1.5).toFloat )
          //               if( ang != value ) {
          // val m = /* control. */ spec.map(ang)
          if (instant) setControl(/* control, */ ang /* m */, instant = true)
          ang
        } else /* control. */ value // spec.inverseMap(value /* .currentApprox */)
      val dr = new Drag(ang, vStart, instant)
      drag = Some(dr)
      true
    } else false
  }

  def removeMapping()(implicit tx: S#Tx): Unit = setControlTxn(value)

  private def setControl(v: Double, instant: Boolean): Unit =
    atomic { implicit t =>
      if (instant) {
        setControlTxn(v)
        // } else t.withTransition(main.transition(t.time)) {
        //  c.v = v
      }
    }

  private def setControlTxn(v: Double)(implicit tx: S#Tx): Unit = {
    val attr = parent.objH().attr
    val vc   = DoubleEx.newConst[S](v)
    attr[DoubleElem](key) match {
      case Some(Expr.Var(vr)) => vr() = vc
      case _ => attr.put(key, Obj(DoubleElem(DoubleEx.newVar(vc))))
    }
  }

  override def itemDragged(vi: VisualItem, e: MouseEvent, pt: Point2D): Unit =
    drag.foreach { dr =>
      val dy = r.getCenterY - pt.getY
      val dx = pt.getX - r.getCenterX
      //            val ang  = -math.atan2( dy, dx )
      val ang = (((-math.atan2(dy, dx) / math.Pi + 3.5) % 2.0) - 0.25) / 1.5
      val vEff = math.max(0.0, math.min(1.0, dr.valueStart + (ang - dr.angStart)))
      //            if( vEff != value ) {
      // val m = /* control. */ spec.map(vEff)
      if (dr.instant) {
        setControl(/* control, */ vEff /* m */, instant = true)
      } else {
        dr.dragValue = vEff // m
      }
      //            }
    }

  override def itemReleased(vi: VisualItem, e: MouseEvent, pt: Point2D): Unit =
    drag foreach { dr =>
      if (!dr.instant) {
        setControl(/* control, */ dr.dragValue, instant = false)
      }
      drag = None
    }

  protected def boundsResized(): Unit = {
    // val pContArc = new Arc2D.Double(0, 0, r.getWidth, r.getHeight, -45, 270, Arc2D.PIE)
    gArc.setArc(0, 0, r.getWidth, r.getHeight, -45, 270, Arc2D.PIE)
    containerArea.reset()
    containerArea.add(new Area(gArc))
    containerArea.subtract(new Area(innerE))
    gp.append(containerArea, false)
    renderedValue = Double.NaN // triggers updateRenderValue
  }

  private def updateRenderValue(v: Double): Unit = {
    renderedValue = v
    // val vn = /* control. */ spec.inverseMap(v)
    //println( "updateRenderValue( " + control.name + " ) from " + v + " to " + vn )
    val angExtent = (v /* vn */ * 270).toInt
    val angStart  = 225 - angExtent
    // val pValArc   = new Arc2D.Double(0, 0, r.getWidth, r.getHeight, angStart, angExtent, Arc2D.PIE)
    gArc.setArc(0, 0, r.getWidth, r.getHeight, angStart, angExtent, Arc2D.PIE)
    valueArea.reset()
    valueArea.add(new Area(gArc))
    valueArea.subtract(new Area(innerE))
  }

  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit = {
    val v = value // .currentApprox
    if (renderedValue != v) updateRenderValue(v)

    g.setColor(if (mapped) colrMapped else /* if (gliding) colrGliding else */ colrManual)
    g.fill(valueArea)
    drag foreach { dr =>
      if (!dr.instant) {
        g.setColor(colrAdjust)
        val ang   = ((1.0 - /* control. */ dr.dragValue /* spec.inverseMap(dr.dragValue) */) * 1.5 - 0.25) * math.Pi
        val cos   = math.cos(ang)
        val sin   = math.sin(ang)
        val x0    = (1 + cos) * diam05
        val y0    = (1 - sin) * diam05
        // val lin = new Line2D.Double(x0, y0, x0 - (cos * diam * 0.2), y0 + (sin * diam * 0.2))
        gLine.setLine(x0, y0, x0 - (cos * diam * 0.2), y0 + (sin * diam * 0.2))
        val strkOrig = g.getStroke
        g.setStroke(strkThick)
        g.draw(gLine)
        g.setStroke(strkOrig)
      }
    }
    g.setColor(ColorLib.getColor(vi.getStrokeColor))
    g.draw(gp)

    drawName(g, vi, diam * vi.getSize.toFloat * 0.33333f)
  }
}