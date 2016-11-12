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

import java.awt.Graphics2D
import java.awt.event.MouseEvent
import java.awt.geom.{Arc2D, Area, Point2D}

import de.sciss.lucre.expr.{Expr, Type}
import de.sciss.lucre.stm.{Disposable, Obj, Sys, TxnLike}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.lucre.{expr, stm}
import de.sciss.nuages.NuagesAttribute.{Input, Parent}
import prefuse.data.{Node => PNode}
import prefuse.visual.VisualItem

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref
import scala.language.higherKinds
import scala.swing.Color

object NuagesAttrInputImpl {
  private final class Drag(val angStart: Double, val valueStart: Vec[Double], val instant: Boolean) {
    var dragValue = valueStart
  }
}
trait NuagesAttrInputImpl[S <: SSys[S]]
  extends RenderAttrValue     [S]
  with AttrInputKeyControl    [S]
  with NuagesAttrInputBase    [S]
  with NuagesAttribute.Numeric {

  import NuagesAttrInputImpl.Drag
  import NuagesDataImpl._
  import TxnLike.peer

  // ---- abstract ----

  type Ex[~ <: Sys[~]] <: expr.Expr[~, A]

  protected def tpe: Type.Expr[A, Ex]

  protected def mkConst(v: Vec[Double])(implicit tx: S#Tx): Ex[S] with Expr.Const[S, A]

  protected def valueText(v: Vec[Double]): String

  protected def drawAdjust(g: Graphics2D, v: Vec[Double]): Unit

  // ---- impl ----

  final protected def nodeSize = 1f

  private[this] var drag: Drag      = null

  private[this] val objH = Ref.make[(stm.Source[S#Tx, Ex[S]], Disposable[S#Tx])]()  // object and its observer

  protected final def spec: ParamSpec = attribute.spec

  final def main: NuagesPanel[S]  = attribute.parent.main

  private[this] def atomic[B](fun: S#Tx => B): B = main.transport.scheduler.cursor.step(fun)

  def name: String = attribute.name

  private[this] def isTimeline: Boolean = attribute.parent.main.isTimeline

  protected final def valueColor: Color = if (editable) colrManual else colrMapped

  final def input(implicit tx: S#Tx): Obj[S] = objH()._1()

  final def collect[B](pf: PartialFunction[Input[S], B])(implicit tx: S#Tx): Iterator[B] =
    if (pf.isDefinedAt(this)) Iterator.single(pf(this)) else Iterator.empty

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
  }

  // use only on the EDT
  protected final var editable: Boolean = _

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

  private[this] def updateValueAndRefresh(v: A)(implicit tx: S#Tx): Unit =
    main.deferVisTx {
      valueA = v
      damageReport(pNode)
    }

  def tryConsume(newOffset: Long, to: Obj[S])(implicit tx: S#Tx): Boolean = to.tpe == this.tpe && {
    val newObj = to.asInstanceOf[Ex[S]]
    objH.swap(setObject(newObj))._2.dispose()
    val newEditable = tpe.Var.unapply(newObj).isDefined
    val newValue    = newObj.value
    main.deferVisTx { editable = newEditable }
    updateValueAndRefresh(newValue)
    true
  }

  final def init(obj: Ex[S], parent: Parent[S])(implicit tx: S#Tx): this.type = {
    editable          = tpe.Var.unapply(obj).isDefined
    valueA            = obj.value
    objH()            = setObject(obj)
    inputParent       = parent
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
      } else numericValue
      drag = new Drag(ang, vStart, instant = instant)
      true
    } else false
  }

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

  final protected def boundsResized(): Unit = updateContainerArea()

  protected def renderDrag(g: Graphics2D): Unit = {
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
  }

  final protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit = {
    renderValueDetail(g, vi)
    val isDrag = drag != null
    drawLabel(g, vi, diam * vi.getSize.toFloat * 0.33333f, if (isDrag) valueText(drag.dragValue) else name)
  }
}