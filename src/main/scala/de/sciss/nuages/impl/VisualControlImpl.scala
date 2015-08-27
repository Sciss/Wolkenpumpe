/*
 *  VisualControlImpl.scala
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
package impl

import java.awt.event.MouseEvent
import java.awt.geom.{Arc2D, Area, GeneralPath, Point2D}
import java.awt.{Graphics2D, Shape}

import de.sciss.lucre.expr.{DoubleObj, Expr}
import de.sciss.lucre.stm
import de.sciss.lucre.swing.requireEDT
import de.sciss.lucre.synth.{Synth, Sys}
import de.sciss.synth.proc.Scan
import prefuse.util.ColorLib
import prefuse.visual.VisualItem

import scala.concurrent.stm.Ref

object VisualControlImpl {
  private val defaultSpec = ParamSpec()

  private def getSpec[S <: Sys[S]](parent: VisualObj[S], key: String)(implicit tx: S#Tx): ParamSpec =
    parent.objH().attr.$[ParamSpec.Obj](s"$key-${ParamSpec.Key}").map(_.value).getOrElse(defaultSpec)

  def scalar[S <: Sys[S]](parent: VisualObj[S], key: String,
                          dObj: DoubleObj[S])(implicit tx: S#Tx): VisualControl[S] = {
    val value = dObj.value
    val spec  = getSpec(parent, key)
    apply(parent, key = key, spec = spec, value = value, mapping = None)
  }

  def scan[S <: Sys[S]](parent: VisualObj[S], key: String,
                        sObj: Scan[S])(implicit tx: S#Tx): VisualControl[S] = {
    val value = 0.5 // XXX TODO
    val spec  = getSpec(parent, key)
    val scan  = sObj
    val res = apply(parent, key = key, spec = spec, value = value, mapping = Some(new MappingImpl(tx.newHandle(scan))))
    res
  }

  private def apply[S <: Sys[S]](parent: VisualObj[S], key: String, spec: ParamSpec, value: Double,
                                 mapping: Option[VisualControl.Mapping[S]])
                                (implicit tx: S#Tx): VisualControl[S] = {
    val res   = new VisualControlImpl(parent, key = key, spec = spec, value = value, mapping = mapping)
    res.init()
    res
  }

  private final class Drag(val angStart: Double, val valueStart: Double, val instant: Boolean) {
    var dragValue = valueStart
  }

  private final class MappingImpl[S <: Sys[S]](scanH: stm.Source[S#Tx, Scan[S]]) extends VisualControl.Mapping[S] {
    val synth   = Ref(Option.empty[Synth])
    var source  = Option.empty[VisualScan[S]]

    def scan(implicit tx: S#Tx): Scan[S] = scanH()
  }
}
final class VisualControlImpl[S <: Sys[S]] private(val parent: VisualObj[S], val key: String,
                                                   val /* var */ spec: ParamSpec, @volatile var value: Double,
                                                   val mapping: Option[VisualControl.Mapping[S]])
  extends VisualParamImpl[S] with VisualControl[S] {

  import VisualControlImpl.Drag
  import VisualDataImpl._

  protected def nodeSize = 1f

  private val mapped: Boolean = mapping.isDefined

  private var renderedValue = Double.NaN
  private val containerArea = new Area()
  private val valueArea     = new Area()

  private var drag: Drag = null

  private val spikes: Shape = if (spec.warp == IntWarp) {
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

  def dispose()(implicit tx: S#Tx): Unit = {
    mapping.foreach { m =>
      m.synth.swap(None)(tx.peer).foreach(_.dispose())
    }
    parent.params.remove(key)(tx.peer)
    main.deferVisTx(disposeGUI())
  }

  private def disposeGUI(): Unit = {
    val _vi = main.visualization.getVisualItem(NuagesPanel.GROUP_GRAPH, pNode)
    parent.aggr.removeItem(_vi)
    main.graph.removeNode(pNode)
  }

  def init()(implicit tx: S#Tx): this.type = {
    parent.params.put(key, this)(tx.peer)
    main.deferVisTx(initGUI())
    mapping.foreach { m =>
      main.assignMapping(source = m.scan, vSink = this)
    }
    parent.params.put(key, this)(tx.peer).foreach(_.dispose())
    this
  }

  private def initGUI(): Unit = {
    requireEDT()
    mkPNodeAndEdge()
    main.initNodeGUI(parent, this, None /* locO */)
    // val old = parent.params.get(vc.key)
    mapping.foreach { m =>
      m.source.foreach { vSrc =>
        main.graph.addEdge(vSrc.pNode, pNode)
        vSrc.mappings += this
      }
    }
    // old.foreach(removeControlGUI(vp, _))
  }

  override def itemPressed(vi: VisualItem, e: MouseEvent, pt: Point2D): Boolean = {
    // if (!vProc.isAlive) return false
    if (mapped) return true

    //         if( super.itemPressed( vi, e, pt )) return true

    if (containerArea.contains(pt.getX - r.getX, pt.getY - r.getY)) {
      val dy      = r.getCenterY - pt.getY
      val dx      = pt.getX - r.getCenterX
      val ang0    = math.max(0.0, math.min(1.0, (((-math.atan2(dy, dx) / math.Pi + 3.5) % 2.0) - 0.25) / 1.5))
      val ang     = if (spec.warp == IntWarp) spec.inverseMap(spec.map(ang0)) else ang0
      val instant = !e.isShiftDown // true // !vProc.state.playing || vProc.state.bypassed || main.transition(0) == Instant
      val vStart  = if (e.isAltDown) {
          //               val res = math.min( 1.0f, (((ang / math.Pi + 3.25) % 2.0) / 1.5).toFloat )
          //               if( ang != value ) {
          // val m = /* control. */ spec.map(ang)
          if (instant) setControl(/* control, */ ang /* m */, instant = true)
          ang
        } else /* control. */ value // spec.inverseMap(value /* .currentApprox */)
      drag = new Drag(ang, vStart, instant = instant)
      true
    } else false
  }

  def removeMapping()(implicit tx: S#Tx): Unit = setControlTxn(value)

  def setControl(v: Double, instant: Boolean): Unit =
    atomic { implicit t =>
      // if (instant) {
        setControlTxn(v)
        // } else t.withTransition(main.transition(t.time)) {
        //  c.v = v
      // }
    }

  private def setControlTxn(v: Double)(implicit tx: S#Tx): Unit = {
    val attr = parent.objH().attr
    val vc   = DoubleObj.newConst[S](v)
    attr.$[DoubleObj](key) match {
      case Some(Expr.Var(vr)) => vr() = vc
      case _ => attr.put(key, DoubleObj.newVar(vc))
    }
  }

  override def itemDragged(vi: VisualItem, e: MouseEvent, pt: Point2D): Unit =
    if (drag != null) {
      val dy = r.getCenterY - pt.getY
      val dx = pt.getX - r.getCenterX
      //            val ang  = -math.atan2( dy, dx )
      val ang   = (((-math.atan2(dy, dx) / math.Pi + 3.5) % 2.0) - 0.25) / 1.5
      val vEff0 = math.max(0.0, math.min(1.0, drag.valueStart + (ang - drag.angStart)))
      val vEff  = if (spec.warp == IntWarp) spec.inverseMap(spec.map(vEff0)) else vEff0
      //            if( vEff != value ) {
      // val m = /* control. */ spec.map(vEff)
      if (drag.instant) {
        setControl(/* control, */ vEff /* m */, instant = true)
      }
      drag.dragValue = vEff // m
    }

  override def itemReleased(vi: VisualItem, e: MouseEvent, pt: Point2D): Unit =
    if (drag != null) {
      if (!drag.instant) {
        setControl(/* control, */ drag.dragValue, instant = false)
      }
      drag = null
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

  // changes `gLine`
  private def setSpine(v: Double): Unit = {
    val ang   = ((1.0 - v) * 1.5 - 0.25) * math.Pi
    val cos   = math.cos(ang)
    val sin   = math.sin(ang)
    val x0    = (1 + cos) * diam05
    val y0    = (1 - sin) * diam05
    gLine.setLine(x0, y0, x0 - (cos * diam * 0.2), y0 + (sin * diam * 0.2))
  }

  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit = {
    val v = value // .currentApprox
    if (renderedValue != v) updateRenderValue(v)

    g.setColor(if (mapped) colrMapped else /* if (gliding) colrGliding else */ colrManual)
    g.fill(valueArea)
    val isDrag = drag != null
    if (isDrag) {
      if (!drag.instant) {
        g.setColor(colrAdjust)
        setSpine(drag.dragValue)
        val strkOrig = g.getStroke
        g.setStroke(strkThick)
        g.draw(gLine)
        g.setStroke(strkOrig)
      }
    }
    g.setColor(ColorLib.getColor(vi.getStrokeColor))
    g.draw(gp)

    if (spikes != null) {
      val strkOrig = g.getStroke
      g.setStroke(strkDotted)
      g.draw(spikes)
      g.setStroke(strkOrig)
    }

    drawLabel(g, vi, diam * vi.getSize.toFloat * 0.33333f, if (isDrag) valueText(drag.dragValue) else name)
  }


  private def valueText(v: Double): String = {
    val m = spec.map(v)
    if (spec.warp == IntWarp) m.toInt.toString
    else {
      if (m == Double.PositiveInfinity) "Inf"
      else if (m == Double.NegativeInfinity) "-Inf"
      else if (java.lang.Double.isNaN(m)) "NaN"
      else new java.math.BigDecimal(m, threeDigits).toPlainString
    }
  }
}