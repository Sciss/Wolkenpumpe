/*
 *  NuagesAttrInputImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2016 Hanns Holger Rutz. All rights reserved.
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

import de.sciss.lucre.expr.{Expr, Type}
import de.sciss.lucre.stm.{TxnLike, Obj, Disposable, Sys}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.lucre.{expr, stm}
import prefuse.data.{Node => PNode}
import prefuse.util.ColorLib
import prefuse.visual.VisualItem

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref
import scala.language.higherKinds

object NuagesAttrInputImpl {
  private final class Drag(val angStart: Double, val valueStart: Vec[Double], val instant: Boolean) {
    var dragValue = valueStart
  }
}
trait NuagesAttrInputImpl[S <: SSys[S]]
  extends NuagesDataImpl[S]
  with AttrInputKeyControl[S]
  with NuagesAttribute.Input[S] {

  import NuagesAttrInputImpl.Drag
  import NuagesDataImpl._
  import TxnLike.peer

  // ---- abstract ----

  type A

  type Ex[~ <: Sys[~]] <: expr.Expr[~, A]

  protected def tpe: Type.Expr[A, Ex]

  protected def mkConst(v: Vec[Double])(implicit tx: S#Tx): Ex[S] with Expr.Const[S, A]

  protected def renderValueUpdated(): Unit

  protected def valueText(v: Vec[Double]): String

  protected def drawAdjust(g: Graphics2D, v: Vec[Double]): Unit

  // ---- impl ----

  final protected var renderedValue: A = null.asInstanceOf[A] // invalidRenderedValue

  private[this] var renderedValid: Boolean = false

  @volatile
  protected final var valueA: A = _

  final protected def nodeSize = 1f

  private[this] val containerArea   = new Area()
  protected final val valueArea     = new Area()

  private[this] var drag: Drag      = null

  private[this] val objH = Ref.make[(stm.Source[S#Tx, Ex[S]], Disposable[S#Tx])]()  // object and its observer

  private[this] def spec: ParamSpec = attribute.spec

  def main: NuagesPanel[S]  = attribute.parent.main

  private[this] def atomic[A](fun: S#Tx => A): A = main.transport.scheduler.cursor.step(fun)

  def name: String = attribute.name

  private[this] def isTimeline: Boolean = attribute.parent.main.isTimeline

  private[this] def setControlTxn(v: Vec[Double])(implicit tx: S#Tx): Unit = {
    val nowConst: Ex[S] = mkConst(v) // IntelliJ highlight error SCL-9713
    val before = objH()._1()

    // XXX TODO --- if we overwrite a time value, we should nevertheless update the tpe.Var instead
    if (!editable || isTimeline) {
      inputParent.updateChild(before = before, now = tpe.newVar[S](nowConst))
    } else {
      val Var = tpe.Var
      val Var(vr) = before
      vr() = nowConst
    }
    //    val attr = parent.obj.attr
    //    val vc   = DoubleObj.newConst[S](v.head)
    //    attr.$[DoubleObj](key) match {
    //      case Some(DoubleObj.Var(vr)) => vr() = vc
    //      case _ => attr.put(key, DoubleObj.newVar(vc))
    //    }
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

  // use only on the EDT
  private[this] var editable: Boolean = _

  final protected def updateValueAndRefresh(v: A)(implicit tx: S#Tx): Unit =
    main.deferVisTx {
      valueA = v
      val _vis = main.visualization
      val visItem = _vis.getVisualItem(NuagesPanel.GROUP_GRAPH, pNode)
      _vis.damageReport(visItem, visItem.getBounds)
    }

  def dispose()(implicit tx: S#Tx): Unit = {
    objH()._2.dispose()
    //    mapping.foreach { m =>
    //      m.synth.swap(None)(tx.peer).foreach(_.dispose())
    //    }
    main.deferVisTx(disposeGUI())
  }

  private[this] def disposeGUI(): Unit = {
    log(s"disposeGUI($name)")
    attribute.removePNode(this, _pNode)
    main.graph.removeNode(_pNode)
    // val vi = main.visualization.getVisualItem(NuagesPanel.GROUP_GRAPH, _pNode)
    // val aggr = attribute.parent.aggr
    // assert(!aggr.containsItem(vi), s"still in aggr $aggr@${aggr.hashCode.toHexString}: $vi@${vi.hashCode.toHexString}")
  }

  def tryConsume(to: Obj[S])(implicit tx: S#Tx): Boolean = to.tpe == this.tpe && {
    val newObj = to.asInstanceOf[Ex[S]]
    objH.swap(setObject(newObj))._2.dispose()
    val newEditable = tpe.Var.unapply(newObj).isDefined
    val newValue    = newObj.value
    main.deferVisTx { editable = newEditable }
    updateValueAndRefresh(newValue)
    true
  }

  final def init(obj: Ex[S])(implicit tx: S#Tx): this.type = {
    editable  = tpe.Var.unapply(obj).isDefined
    valueA    = obj.value
    // SCAN
    //    mapping.foreach { m =>
    //      main.assignMapping(source = m.scan, vSink = this)
    //    }
    objH() = setObject(obj)
    main.deferVisTx(initGUI())
    this
  }

  private[this] def setObject(obj: Ex[S])(implicit tx: S#Tx): (stm.Source[S#Tx, Ex[S]], Disposable[S#Tx]) = {
    implicit val ser = tpe.serializer[S]
    val h         = tx.newHandle(obj)
    val observer  = obj.changed.react { implicit tx => upd =>
      updateValueAndRefresh(upd.now)
    }
    (h, observer)
  }

  private[this] var _pNode: PNode = _

  final def pNode: PNode = {
    if (_pNode == null) throw new IllegalStateException(s"Component $this has no initialized GUI")
    _pNode
  }

  private[this] def initGUI(): Unit = mkPNode()

  private[this] def mkPNode(): Unit = {
    if (_pNode != null) throw new IllegalStateException(s"Component $this has already been initialized")
    log(s"mkPNode($name)")
    _pNode  = main.graph.addNode()
    val vis = main.visualization
    val vi  = vis.getVisualItem(NuagesPanel.GROUP_GRAPH, _pNode)
    vi.set(NuagesPanel.COL_NUAGES, this)
    attribute.addPNode(this, _pNode, isFree = true)
  }

  final override def itemPressed(vi: VisualItem, e: MouseEvent, pt: Point2D): Boolean = {
    // if (!vProc.isAlive) return false
    if (!editable) return true

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

//  final def removeMapping()(implicit tx: S#Tx): Unit = setControlTxn(value)

  protected final def setControl(v: Vec[Double], instant: Boolean): Unit =
    atomic { implicit tx =>
      setControlTxn(v)
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
    // renderedValue = invalidRenderedValue // Vector.empty // Double.NaN // triggers updateRenderValue
    renderedValid = false
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
    if (!renderedValid || renderedValue != v) {
      renderedValue = v
      renderValueUpdated()
      renderedValid = true
    }

    g.setColor(if (editable) colrManual else colrMapped)
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