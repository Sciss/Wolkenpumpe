package de.sciss.nuages
package impl

import java.awt.event.MouseEvent
import java.awt.{Shape, Graphics2D}
import java.awt.geom.{Arc2D, Point2D, GeneralPath, Area}

import de.sciss.lucre.stm.{Disposable, Obj}
import de.sciss.lucre.synth.{Sys => SSys}
import prefuse.data.{Node => PNode}
import prefuse.util.ColorLib
import prefuse.visual.VisualItem

import scala.collection.immutable.{IndexedSeq => Vec}

object NuagesAttrInputImpl {
  private final class Drag(val angStart: Double, val valueStart: Vec[Double], val instant: Boolean) {
    var dragValue = valueStart
  }
}
trait NuagesAttrInputImpl[S <: SSys[S]] extends NuagesDataImpl[S] with NuagesAttribute.Input[S] {
  import NuagesAttrInputImpl.Drag
  import NuagesDataImpl._

  // ---- abstract ----

  type A

  protected var valueA: A

  protected def setControlTxn(v: Vec[Double])(implicit tx: S#Tx): Unit

  protected def init1(obj: Obj[S])(implicit tx: S#Tx): Unit

  protected def renderValueUpdated(): Unit

  protected def valueText(v: Vec[Double]): String

  protected def drawAdjust(g: Graphics2D, v: Vec[Double]): Unit

  //  protected def nodeProvider: NuagesAttribute.NodeProvider[S]

  protected def editable: Boolean

  // ---- impl ----

  final protected var renderedValue: A = null.asInstanceOf[A] // invalidRenderedValue

  private[this] var renderedValid: Boolean = false

  final protected def nodeSize = 1f

  private[this] val containerArea   = new Area()
  protected final val valueArea     = new Area()

  private[this] var drag: Drag      = null

  private[this] def spec: ParamSpec = attribute.spec

  def main: NuagesPanel[S]  = attribute.parent.main

  private[this] def atomic[A](fun: S#Tx => A): A = main.transport.scheduler.cursor.step(fun)

  def name: String = attribute.name

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
    //    mapping.foreach { m =>
    //      m.synth.swap(None)(tx.peer).foreach(_.dispose())
    //    }
    ??? // parent.params.remove(key)(tx.peer)
    main.deferVisTx(disposeGUI())
  }

  private[this] def disposeGUI(): Unit = {
    ??? // nodeProvider.releasePNode(this)
  }

  final def init(obj: Obj[S])(implicit tx: S#Tx): this.type = {
    ??? // parent.params.put(key, this)(tx.peer) // .foreach(_.dispose())
    main.deferVisTx(initGUI())
    // SCAN
    //    mapping.foreach { m =>
    //      main.assignMapping(source = m.scan, vSink = this)
    //    }
    init1(obj)
    this
  }

  private[this] var _pNode: PNode = _

  final def pNode: PNode = {
    if (_pNode == null) throw new IllegalStateException(s"Component $this has no initialized GUI")
    _pNode
  }

  private[this] def initGUI(): Unit = {
    // requireEDT()
    ??? // _pNode = nodeProvider.acquirePNode(this)
    // mkPNodeAndEdge()
    // this is now done by `assignMapping`:
    //    mapping.foreach { m =>
    //      m.source.foreach { vSrc =>
    //        main.graph.addEdge(vSrc.pNode, pNode)
    //        vSrc.mappings += this
    //      }
    //    }
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