/*
 *  NuagesAttrInputImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2017 Hanns Holger Rutz. All rights reserved.
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
import java.awt.geom.Point2D

import de.sciss.lucre.expr.{Expr, Type}
import de.sciss.lucre.stm.{Disposable, Obj, Sys, TxnLike}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.lucre.{expr, stm}
import de.sciss.nuages.NuagesAttribute.{Input, Parent}
import de.sciss.serial.Serializer
import de.sciss.synth.Curve
import de.sciss.synth.proc.{EnvSegment, TimeRef}
import prefuse.data.{Node => PNode}
import prefuse.visual.VisualItem

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref
import scala.language.higherKinds
import scala.swing.Color

object NuagesAttrInputImpl {
  private final class Drag(val angStart: Double, val valueStart: Vec[Double], val instant: Boolean) {
    var dragValue: Vec[Double] = valueStart
  }
}
trait NuagesAttrInputImpl[S <: SSys[S]]
  extends RenderAttrValue     [S]
  with AttrInputKeyControl    [S]
  with NuagesAttrSingleInput  [S]
  with NuagesAttribute.Numeric { self =>

  import NuagesAttrInputImpl.Drag
  import NuagesDataImpl._
  import TxnLike.peer

  // ---- abstract ----

  type B

  type Ex[~ <: Sys[~]] <: expr.Expr[~, B]

  protected def tpe: Type.Expr[B, Ex]

  protected def mkConst(v: Vec[Double])(implicit tx: S#Tx): Ex[S] with Expr.Const[S, B]

  protected def mkEnvSeg(start: Ex[S], curve: Curve)(implicit tx: S#Tx): EnvSegment.Obj[S]

  protected def valueText(v: Vec[Double]): String

  protected def drawAdjust(g: Graphics2D, v: Vec[Double]): Unit

  // ---- impl ----

  final protected def nodeSize = 1f

  private[this] var drag: Drag = _

  private[this] val objH = Ref.make[(stm.Source[S#Tx, Ex[S]], Disposable[S#Tx])]()  // object and its observer

  protected final def spec: ParamSpec = attribute.spec

  final def main: NuagesPanel[S]  = attribute.parent.main

  private def atomic[B](fun: S#Tx => B): B = main.transport.scheduler.cursor.step(fun)

  def name: String = attribute.name

  protected final def valueColor: Color = if (editable) colrManual else colrMapped

  final def input(implicit tx: S#Tx): Obj[S] = objH()._1()

  private def setControlTxn(v: Vec[Double], durFrames: Long)(implicit tx: S#Tx): Unit = {
    val nowConst: Ex[S] = mkConst(v) // IntelliJ highlight error SCL-9713
    val before  : Ex[S] = objH()._1()

    // XXX TODO --- if we overwrite a time value, we should nevertheless update the tpe.Var instead
    if (!editable || isTimeline) {
      val nowVar = tpe.newVar[S](nowConst)
      if (durFrames == 0L)
        inputParent.updateChild(before = before, now = nowVar, dt = 0L)
      else {
        val seg = mkEnvSeg(before, Curve.lin) // EnvSegment.Obj.ApplySingle()
        inputParent.updateChild(before = before, now = nowVar, dt = durFrames )
        inputParent.updateChild(before = before, now = seg   , dt = 0L        )
      }

    } else {
      if (durFrames > 0) println("Warning: setControlTxn drops `durFrames` when not a timeline")
      val Var = tpe.Var
      val Var(vr) = before
      vr() = nowConst
    }
  }

  // use only on the EDT
  private[this] var editable: Boolean = _

  def dispose()(implicit tx: S#Tx): Unit = {
    objH()._2.dispose()
    //    mapping.foreach { m =>
    //      m.synth.swap(None)(tx.peer).foreach(_.dispose())
    //    }
    main.deferVisTx(disposeGUI())
  }

  private def disposeGUI(): Unit = {
    log(s"disposeGUI($name)")
    attribute.removePNode(this, _pNode)
    main.graph.removeNode(_pNode)
    // val vi = main.visualization.getVisualItem(NuagesPanel.GROUP_GRAPH, _pNode)
    // val aggr = attribute.parent.aggr
    // assert(!aggr.containsItem(vi), s"still in aggr $aggr@${aggr.hashCode.toHexString}: $vi@${vi.hashCode.toHexString}")
  }

  protected def updateValueAndRefresh(v: B)(implicit tx: S#Tx): Unit

  def tryConsume(newOffset: Long, to: Obj[S])(implicit tx: S#Tx): Boolean = to.tpe == this.tpe && {
    val newObj = to.asInstanceOf[Ex[S]]
    objH.swap(setObject(newObj))._2.dispose()
    val newEditable = tpe.Var.unapply(newObj).isDefined
    val newValue    = newObj.value
    main.deferVisTx { editable = newEditable }
    updateValueAndRefresh(newValue)
    true
  }

  def init(obj: Ex[S], parent: Parent[S])(implicit tx: S#Tx): this.type = {
    editable    = tpe.Var.unapply(obj).isDefined
//    valueA      = obj.value
    objH()      = setObject(obj)
    inputParent = parent
    main.deferVisTx(initGUI())
    this
  }

  private def setObject(obj: Ex[S])(implicit tx: S#Tx): (stm.Source[S#Tx, Ex[S]], Disposable[S#Tx]) = {
    implicit val ser: Serializer[S#Tx, S#Acc, Ex[S]] = tpe.serializer[S]
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

  private def initGUI(): Unit = mkPNode()

  private def mkPNode(): Unit = {
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
      setControlTxn(v, if (instant) 0L else (TimeRef.SampleRate * 10).toLong)
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

trait NuagesAttrInputVarImpl[S <: SSys[S]] extends NuagesAttrInputImpl[S] {
  type B = A

  @volatile
  final protected var valueA: A = _

  protected final def updateValueAndRefresh(v: A)(implicit tx: S#Tx): Unit =
    main.deferVisTx {
      valueA = v
      damageReport(pNode)
    }

  override def init(obj: Ex[S], parent: Parent[S])(implicit tx: S#Tx): this.type = {
    valueA = obj.value
    super.init(obj, parent)
  }
}