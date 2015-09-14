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

import de.sciss.lucre.expr.{DoubleObj, DoubleVector}
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, Obj}
import de.sciss.lucre.swing.requireEDT
import de.sciss.lucre.synth.{Synth, Sys}
import de.sciss.numbers
import de.sciss.synth.proc.Scan
import prefuse.util.ColorLib
import prefuse.visual.VisualItem

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref

object VisualControlImpl {
  private val defaultSpec = ParamSpec()

  private def getSpec[S <: Sys[S]](parent: VisualObj[S], key: String)(implicit tx: S#Tx): ParamSpec =
    parent.obj.attr.$[ParamSpec.Obj](s"$key-${ParamSpec.Key}").map(_.value).getOrElse(defaultSpec)

  def scalar[S <: Sys[S]](parent: VisualObj[S], key: String,
                          obj: DoubleObj[S])(implicit tx: S#Tx): VisualControl[S] = {
    val value = obj.value
    val spec  = getSpec(parent, key)
    // apply(parent, dObj, key = key, spec = spec, value = value, mapping = None)
    new VisualScalarControl[S](parent, key = key, spec = spec, valueA = value, mapping = None).init(obj)
  }

  def vector[S <: Sys[S]](parent: VisualObj[S], key: String,
                          obj: DoubleVector[S])(implicit tx: S#Tx): VisualControl[S] = {
    val value = obj.value
    val spec  = getSpec(parent, key)
    new VisualVectorControl[S](parent, key = key, spec = spec, valueA = value, mapping = None).init(obj)
  }

  private final val scanValue = Vector(0.5): Vec[Double] // XXX TODO

  def scan[S <: Sys[S]](parent: VisualObj[S], key: String,
                        obj: Scan[S])(implicit tx: S#Tx): VisualControl[S] = {
    val value   = scanValue
    val spec    = getSpec(parent, key)
    val mapping = Some(new MappingImpl(tx.newHandle(obj)))
    // val res   = apply(parent, sObj, key = key, spec = spec, value = value, mapping = Some())
    new VisualVectorControl[S](parent, key = key, spec = spec, valueA = value, mapping = mapping).init(obj)
  }

//  private def apply[S <: Sys[S]](parent: VisualObj[S], obj: Obj[S], key: String, spec: ParamSpec, value: Double,
//                                 mapping: Option[VisualControl.Mapping[S]])
//                                (implicit tx: S#Tx): VisualControl[S] = {
//    new VisualScalarControl[S](parent, key = key, spec = spec, valueA = value, mapping = mapping).init(obj)
//  }

  private final class Drag(val angStart: Double, val valueStart: Vec[Double], val instant: Boolean) {
    var dragValue = valueStart
  }

  private final class MappingImpl[S <: Sys[S]](scanH: stm.Source[S#Tx, Scan[S]]) extends VisualControl.Mapping[S] {
    val synth   = Ref(Option.empty[Synth])
    var source  = Option.empty[VisualScan[S]]

    def scan(implicit tx: S#Tx): Scan[S] = scanH()
  }
}

final class VisualScalarControl[S <: Sys[S]](val parent: VisualObj[S], val key: String, val spec: ParamSpec,
                                             @volatile var valueA: Double,
                                             val mapping: Option[VisualControl.Mapping[S]])
 extends VisualControlImpl[S] {

  type A = Double

  def value: Vec[Double] = Vector(valueA)
  def value_=(v: Vec[Double]): Unit = {
    if (v.size != 1) throw new IllegalArgumentException("Trying to set multi-channel parameter on scalar control")
    valueA = v.head
  }

  def value1_=(v: Double): Unit = valueA = v

  protected def invalidRenderedValue: Double = Double.NaN

  def numChannels = 1

  protected def setControlTxn(v: Vec[Double])(implicit tx: S#Tx): Unit = {
    if (v.size != 1) throw new IllegalArgumentException("Trying to set multi-channel parameter on scalar control")
    val attr = parent.obj.attr
    val vc   = DoubleObj.newConst[S](v.head)
    attr.$[DoubleObj](key) match {
      case Some(DoubleObj.Var(vr)) => vr() = vc
      case _ => attr.put(key, DoubleObj.newVar(vc))
    }
  }

  protected def init1(obj: Obj[S])(implicit tx: S#Tx): Unit =
    obj match {
      case dObj: DoubleObj[S] =>
        observers ::= dObj.changed.react { implicit tx => upd =>
          updateValueAndRefresh(upd.now)
        }
      case _ =>
    }

  import VisualDataImpl.gLine

  protected def renderValueUpdated(): Unit = renderValueUpdated1(renderedValue)

  protected def valueText(v: Vec[Double]): String = valueText1(v.head)

  protected def drawAdjust(g: Graphics2D, v: Vec[Double]): Unit = {
    setSpine(v.head)
    g.draw(gLine)
  }
}

final class VisualVectorControl[S <: Sys[S]](val parent: VisualObj[S], val key: String, val spec: ParamSpec,
                                             @volatile var valueA: Vec[Double],
                                             val mapping: Option[VisualControl.Mapping[S]])
  extends VisualControlImpl[S] {

  type A = Vec[Double]

  private[this] var allValuesEqual = false

  def value: Vec[Double] = valueA
  def value_=(v: Vec[Double]): Unit = {
//    if (v.size != valueA.size)
//      throw new IllegalArgumentException(s"Channel mismatch, expected $numChannels but given ${v.size}")
    valueA = v
  }

  def value1_=(v: Double): Unit = {
    if (valueA.size != 1) throw new IllegalArgumentException(s"Channel mismatch, expected $numChannels but given 1")
    valueA = Vector.empty :+ v
  }

  protected def invalidRenderedValue: Vec[Double] = Vector.empty

  def numChannels = valueA.size

  protected def setControlTxn(v: Vec[Double])(implicit tx: S#Tx): Unit = {
    // if (v.size != 1) throw new IllegalArgumentException("Trying to set multi-channel parameter on scalar control")
    val attr = parent.obj.attr
    val vc   = DoubleVector.newConst[S](v)
    attr.$[DoubleVector](key) match {
      case Some(DoubleVector.Var(vr)) => vr() = vc
      case _ => attr.put(key, DoubleVector.newVar(vc))
    }
  }

  protected def init1(obj: Obj[S])(implicit tx: S#Tx): Unit =
    obj match {
      case dObj: DoubleVector[S] =>
        observers ::= dObj.changed.react { implicit tx => upd =>
          updateValueAndRefresh(upd.now)
        }
      case _ =>
    }

  import VisualDataImpl.{gArc, gEllipse, gLine}

  protected def renderValueUpdated(): Unit = {
    val rv: Vec[Double] = renderedValue // why IntelliJ !?
    val sz = rv.size
    val eq = sz == 1 || (sz > 1 && {
      val v0  = rv.head
      var ch  = 1
      var res = true
      while (ch < sz && res) {
        res = rv(ch) == v0
        ch += 1
      }
      res
    })
    allValuesEqual = eq

    if (eq) {
      renderValueUpdated1(rv.head)
    } else {
      var ch = 0
      val m1 = VisualDataImpl.margin / sz
      val w  = r.getWidth
      val h  = r.getHeight
      var m2 = 0.0
      valueArea.reset()
      while (ch < sz) {
        val v         = rv(ch)
        val vc        = math.max(0, math.min(1, v))
        val angExtent = (vc * 270).toInt
        val angStart  = 225 - angExtent
        val m3        = m2 + m2
        gArc.setArc(m2, m2, w - m3, h - m3, angStart, angExtent, Arc2D.PIE)
        valueArea.add(new Area(gArc))
        m2           += m1
        val m4        = m2 + m2
        gEllipse.setFrame(m2, m2, w - m4, h - m4)
        valueArea.subtract(new Area(gEllipse))
        ch += 1
      }
    }
  }

  protected def valueText(v: Vec[Double]): String =
    if (allValuesEqual) {
      valueText1(v.head)
    } else {
      val s1 = valueText1(v.head)
      val s2 = valueText1(v.last)
      s"$s1…$s2"
    }

  protected def drawAdjust(g: Graphics2D, v: Vec[Double]): Unit =
    if (allValuesEqual || true /* XXX TODO */ ) {
      setSpine(v.head)
      g.draw(gLine)
    } else {
    ???
//    setSpine(v.head)
//    g.draw(gLine)
    }
}

abstract class VisualControlImpl[S <: Sys[S]]  extends VisualParamImpl[S] with VisualControl[S] {
  import VisualControlImpl.Drag
  import VisualDataImpl._

  // ---- abstract ----

  type A

  protected var valueA: A

  protected def setControlTxn(v: Vec[Double])(implicit tx: S#Tx): Unit

  protected def invalidRenderedValue: A

  protected def init1(obj: Obj[S])(implicit tx: S#Tx): Unit

  protected def renderValueUpdated(): Unit

  protected def valueText(v: Vec[Double]): String

  protected def drawAdjust(g: Graphics2D, v: Vec[Double]): Unit

  // ---- impl ----

  final protected var renderedValue: A = invalidRenderedValue

  final protected def nodeSize = 1f

  private[this] val mapped: Boolean = mapping.isDefined

  private[this] val containerArea = new Area()
  protected final val valueArea     = new Area()

  private[this] var drag: Drag = null

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

  final protected var observers = List.empty[Disposable[S#Tx]]

  final protected def updateValueAndRefresh(v: A)(implicit tx: S#Tx): Unit =
    main.deferVisTx {
      valueA = v
      val _vis = main.visualization
      val visItem = _vis.getVisualItem(NuagesPanel.GROUP_GRAPH, pNode)
      _vis.damageReport(visItem, visItem.getBounds)
    }

  def dispose()(implicit tx: S#Tx): Unit = {
    observers.foreach(_.dispose())
    mapping.foreach { m =>
      m.synth.swap(None)(tx.peer).foreach(_.dispose())
    }
    parent.params.remove(key)(tx.peer)
    main.deferVisTx(disposeGUI())
  }

  final def init(obj: Obj[S])(implicit tx: S#Tx): this.type = {
    parent.params.put(key, this)(tx.peer) // .foreach(_.dispose())
    main.deferVisTx(initGUI())
    mapping.foreach { m =>
      main.assignMapping(source = m.scan, vSink = this)
    }
    init1(obj)
    this
  }

  private[this] def initGUI(): Unit = {
    requireEDT()
    mkPNodeAndEdge()
    mapping.foreach { m =>
      m.source.foreach { vSrc =>
        main.graph.addEdge(vSrc.pNode, pNode)
        vSrc.mappings += this
      }
    }
  }

  final override def itemPressed(vi: VisualItem, e: MouseEvent, pt: Point2D): Boolean = {
    // if (!vProc.isAlive) return false
    if (mapped) return true

    if (containerArea.contains(pt.getX - r.getX, pt.getY - r.getY)) {
      val dy      = r.getCenterY - pt.getY
      val dx      = pt.getX - r.getCenterX
      val ang0    = math.max(0.0, math.min(1.0, (((-math.atan2(dy, dx) / math.Pi + 3.5) % 2.0) - 0.25) / 1.5))
      val ang     = if (spec.warp == IntWarp) spec.inverseMap(spec.map(ang0)) else ang0
      val instant = !e.isShiftDown
      val vStart  = if (e.isAltDown) {
          val angV = Vector.fill(numChannels)(ang)
          if (instant) setControl(angV, instant = true)
          angV
        } else value
      drag = new Drag(ang, vStart, instant = instant)
      true
    } else false
  }

  final def removeMapping()(implicit tx: S#Tx): Unit = setControlTxn(value)

  final def setControl(v: Vec[Double], instant: Boolean): Unit =
    atomic { implicit t =>
      // if (instant) {
        setControlTxn(v)
        // } else t.withTransition(main.transition(t.time)) {
        //  c.v = v
      // }
    }

  final override def itemDragged(vi: VisualItem, e: MouseEvent, pt: Point2D): Unit =
    if (drag != null) {
      val dy = r.getCenterY - pt.getY
      val dx = pt.getX - r.getCenterX
      //            val ang  = -math.atan2( dy, dx )
      val ang   = (((-math.atan2(dy, dx) / math.Pi + 3.5) % 2.0) - 0.25) / 1.5
      val vEff0 = drag.valueStart.map { vs =>
        math.max(0.0, math.min(1.0, vs + (ang - drag.angStart)))
      }
      val vEff  = if (spec.warp == IntWarp) vEff0.map { ve => spec.inverseMap(spec.map(ve)) } else vEff0
      //            if( vEff != value ) {
      // val m = /* control. */ spec.map(vEff)
      if (drag.instant) {
        setControl(/* control, */ vEff /* m */, instant = true)
      }
      drag.dragValue = vEff // m
    }

  final override def itemReleased(vi: VisualItem, e: MouseEvent, pt: Point2D): Unit =
    if (drag != null) {
      if (!drag.instant) {
        setControl(/* control, */ drag.dragValue, instant = false)
      }
      drag = null
    }

  final protected def boundsResized(): Unit = {
    // val pContArc = new Arc2D.Double(0, 0, r.getWidth, r.getHeight, -45, 270, Arc2D.PIE)
    gArc.setArc(0, 0, r.getWidth, r.getHeight, -45, 270, Arc2D.PIE)
    containerArea.reset()
    containerArea.add(new Area(gArc))
    containerArea.subtract(new Area(innerE))
    gp.append(containerArea, false)
    renderedValue = invalidRenderedValue // Vector.empty // Double.NaN // triggers updateRenderValue
  }

  // changes `gLine`
  final protected def setSpine(v: Double): Unit = {
    val ang   = ((1.0 - v) * 1.5 - 0.25) * math.Pi
    val cos   = math.cos(ang)
    val sin   = math.sin(ang)
    val x0    = (1 + cos) * diam05
    val y0    = (1 - sin) * diam05
    gLine.setLine(x0, y0, x0 - (cos * diam * 0.2), y0 + (sin * diam * 0.2))
  }

  final protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit = {
    val v = valueA // .currentApprox
    if (renderedValue != v) {
      renderedValue = v
      renderValueUpdated()
    }

    g.setColor(if (mapped) colrMapped else /* if (gliding) colrGliding else */ colrManual)
    g.fill(valueArea)
    val isDrag = drag != null
    if (isDrag) {
      if (!drag.instant) {
        g.setColor(colrAdjust)
        val strkOrig = g.getStroke
        g.setStroke(strkThick)
        drawAdjust(g, drag.dragValue)
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

  final protected def renderValueUpdated1(v: Double): Unit = {
    val vc        = math.max(0, math.min(1, v))
    val angExtent = (vc * 270).toInt
    val angStart  = 225 - angExtent
    // val pValArc   = new Arc2D.Double(0, 0, r.getWidth, r.getHeight, angStart, angExtent, Arc2D.PIE)
    gArc.setArc(0, 0, r.getWidth, r.getHeight, angStart, angExtent, Arc2D.PIE)
    valueArea.reset()
    valueArea.add(new Area(gArc))
    valueArea.subtract(new Area(innerE))
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
}