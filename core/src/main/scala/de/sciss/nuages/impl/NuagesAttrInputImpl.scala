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

import de.sciss.lucre.expr.Type
import de.sciss.lucre.stm.TxnLike.peer
import de.sciss.lucre.stm.{Disposable, Obj, Sys}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.lucre.{expr, stm}
import de.sciss.nuages.NuagesAttribute.Parent
import de.sciss.nuages.impl.NuagesDataImpl.colrManual
import de.sciss.numbers
import de.sciss.serial.Serializer
import de.sciss.synth.Curve
import de.sciss.synth.proc.{EnvSegment, TimeRef}
import prefuse.data.{Node => PNode}
import prefuse.visual.VisualItem

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref
import scala.language.higherKinds
import scala.swing.Color

trait NuagesAttrInputImpl[S <: SSys[S]]
  extends RenderAttrValue     [S]
  with AttrInputKeyControl    [S]
  with NuagesAttrSingleInput  [S]
  with PassAttrInput          [S]
  with NuagesAttribute.Numeric { self =>

  import NuagesDataImpl._

  // ---- abstract ----

  type B

  type Repr[~ <: Sys[~]] <: expr.Expr[~, B]

  protected val tpe: Type.Expr[B, Repr ]

  protected def valueText(v: Vec[Double]): String

  protected def drawAdjust(g: Graphics2D, v: Vec[Double]): Unit

  protected def valueColor: Color

  protected def updateValueAndRefresh(v: B)(implicit tx: S#Tx): Unit

  protected def setControlTxn(v: Vec[Double], durFrames: Long)(implicit tx: S#Tx): Unit

  // ---- impl: state ----

  private[this] var _pNode  : PNode = _
  private[this] var _drag   : NumericAdjustment = _

  protected final val objH = Ref.make[(stm.Source[S#Tx, Repr[S]], Disposable[S#Tx])]()  // object and its observer

  // ---- impl: methods ----

  final def dragOption: Option[NumericAdjustment] = Option(_drag)

  final protected def nodeSize = 1f

  protected final def spec: ParamSpec = attribute.spec

  final def main: NuagesPanel[S]  = attribute.parent.main

  private def atomic[C](fun: S#Tx => C): C = main.transport.scheduler.cursor.step(fun)

  def name: String = attribute.name

  final def input(implicit tx: S#Tx): Obj[S] = objH()._1()

  final def passFrom(that: PassAttrInput[S])(implicit tx: S#Tx): Unit = {
    main.deferVisTx {
      copyFrom(that)
      _pNode  = that.pNode
      _drag   = that.dragOption.orNull
    }
    that.passedTo(this)
  }

  final def passedTo(that: PassAttrInput[S])(implicit tx: S#Tx): Unit = {
    main.deferVisTx { _pNode = null }
    dispose()
  }

  def dispose()(implicit tx: S#Tx): Unit = {
    objH()._2.dispose()
    main.deferVisTx(disposeGUI())
  }

  private def disposeGUI(): Unit = if (_pNode != null) {
    log(s"disposeGUI($name)")
    attribute.removePNode(_pNode)
    main.graph.removeNode(_pNode)
  }

  def tryConsume(newOffset: Long, to: Obj[S])(implicit tx: S#Tx): Boolean = to.tpe == this.tpe && {
    val newObj = to.asInstanceOf[Repr[S]]
    objH.swap(setObject(newObj))._2.dispose()
    // val newEditable = mainType.Var.unapply(newObj).isDefined
    val newValue    = newObj.value
    // main.deferVisTx { editable = newEditable }
    updateValueAndRefresh(newValue)
    true
  }

  def init(obj: Repr[S], parent: Parent[S])(implicit tx: S#Tx): Unit = {
    // editable    = mainType.Var.unapply(obj).isDefined
    objH()      = setObject(obj)
    inputParent = parent
    main.deferVisTx(initGUI())
  }

  private def setObject(obj: Repr[S])(implicit tx: S#Tx): (stm.Source[S#Tx, Repr[S]], Disposable[S#Tx]) = {
    implicit val ser: Serializer[S#Tx, S#Acc, Repr[S]] = tpe.serializer[S]
    val h         = tx.newHandle(obj)
    val observer  = obj.changed.react { implicit tx => upd =>
      updateValueAndRefresh(upd.now)
    }
    (h, observer)
  }

  final def pNode: PNode = {
    if (_pNode == null) throw new IllegalStateException(s"Component $this has no initialized GUI")
    _pNode
  }

  private def initGUI(): Unit = {
    val mkNode = _pNode == null
    if (mkNode) {
      log(s"mkPNode($name) this ")
      _pNode = main.graph.addNode()
    }
    val vis = main.visualization
    val vi  = vis.getVisualItem(NuagesPanel.GROUP_GRAPH, _pNode)
    vi.set(NuagesPanel.COL_NUAGES, this)
    if (mkNode) attribute.addPNode(_pNode, isFree = true)
  }

  override protected def escape(): Boolean = {
    if (_drag != null) {
      _drag = null
      true
    } else super.escape()
  }

  final override def itemPressed(vi: VisualItem, e: MouseEvent, pt: Point2D): Boolean = {
    // if (!vProc.isAlive) return false
    // if (!editable) return true

    if (containerArea.contains(pt.getX - r.getX, pt.getY - r.getY)) {
      val dy      = r.getCenterY - pt.getY
      val dx      = pt.getX - r.getCenterX
      val ang0    = math.max(0.0, math.min(1.0, (((-math.atan2(dy, dx) / math.Pi + 3.5) % 2.0) - 0.25) / 1.5))
      val ang     = if (spec.warp == IntWarp) spec.inverseMap(spec.map(ang0)) else ang0
      val instant = !e.isShiftDown
      val vStart  = if (e.isAltDown) {
        val angV = Vector.fill(numChannels)(ang)
        if (instant) setControl(angV, dur = 0f)
        angV
      } else numericValue
      _drag = new NumericAdjustment(ang, vStart, instant = instant)
      if (!instant) initGlide()
      true
    } else false
  }

  private def initGlide(): Unit = {
    val m = main
    m.acceptGlideTime   = true
    m.glideTime         = 0f
    m.glideTimeSource   = ""
  }

  protected final def setControl(v: Vec[Double], dur: Float): Unit =
    atomic { implicit tx =>
      setControlTxn(v, (TimeRef.SampleRate * dur).toLong)
    }

  final override def itemDragged(vi: VisualItem, e: MouseEvent, pt: Point2D): Unit = {
    val dr = _drag
    if (dr!= null) {
//      if (!dr.isInit) {
//        dr.isInit = true
        if (e.isShiftDown && dr.instant) {
          dr.instant = false
          initGlide()
        }
//      }

      val dy = r.getCenterY - pt.getY
      val dx = pt.getX - r.getCenterX
      val ang = (((-math.atan2(dy, dx) / math.Pi + 3.5) % 2.0) - 0.25) / 1.5
      val vEff0 = dr.valueStart.map { vs =>
        math.max(0.0, math.min(1.0, vs + (ang - dr.angStart)))
      }
      val vEff = if (spec.warp == IntWarp) vEff0.map { ve => spec.inverseMap(spec.map(ve)) } else vEff0
      if (dr.instant) {
        setControl(vEff, dur = 0f)
      }
      dr.dragValue = vEff
    }
  }

  final override def itemReleased(vi: VisualItem, e: MouseEvent, pt: Point2D): Unit =
    if (_drag != null) {
      if (!_drag.instant) {
        val m = main
        m.acceptGlideTime = false
        val dur0          = m.glideTime
        // leave that as a visible indicator:
//        m.glideTime       = 0f
        val dur           = if (dur0 == 0f) 0f else {
          import numbers.Implicits._
          if (dur0 <= 0.1f) dur0 * 2.5f
          else dur0.linexp(0.1f, 1f, 0.25f, 60f)   // extreme :)
        }
        setControl(_drag.dragValue, dur = dur)
      }
      _drag = null
    }

  final protected def boundsResized(): Unit = updateContainerArea()

  protected def renderDrag(g: Graphics2D): Unit = {
    val isDrag = _drag != null
    if (isDrag) {
      if (!_drag.instant) {
        g.setColor(colrAdjust)
        val strokeOrig = g.getStroke
        g.setStroke(strkThick)
        drawAdjust(g, _drag.dragValue)
        g.setStroke(strokeOrig)
      }
    }
  }

  final protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit = {
    renderValueDetail(g, vi)
    val isDrag = _drag != null
    drawLabel(g, vi, diam * vi.getSize.toFloat * 0.33333f, if (isDrag) valueText(_drag.dragValue) else name)
  }
}

trait NuagesAttrInputExprImpl[S <: SSys[S]] extends NuagesAttrInputImpl[S] {

  type B = A

  @volatile
  final protected var valueA: A = _

  protected final def updateValueAndRefresh(v: A)(implicit tx: S#Tx): Unit =
    main.deferVisTx {
      valueA = v
      damageReport(pNode)
    }

  override def init(obj: Repr[S], parent: Parent[S])(implicit tx: S#Tx): Unit = {
    valueA = obj.value
    super.init(obj, parent)
  }

  protected final def valueColor: Color = colrManual // if (editable) colrManual else colrMapped

  protected def mkConst(v: Vec[Double])(implicit tx: S#Tx): Repr[S] // with Expr.Const[S, B]

  protected def mkEnvSeg(start: Repr[S], curve: Curve)(implicit tx: S#Tx): EnvSegment.Obj[S]

  protected final def setControlTxn(v: Vec[Double], durFrames: Long)(implicit tx: S#Tx): Unit = {
    val nowConst: Repr[S]  = mkConst(v)
    val before  : Repr[S]  = objH()._1()

    lazy val nowVar = tpe.newVar[S](nowConst)
    if (durFrames == 0L) {
      before match {
        case tpe.Var(beforeV) => beforeV() = nowConst
        case _ =>
          inputParent.updateChild(before = before, now = nowVar, dt = 0L, clearRight = true)
      }
    } else {
      val seg = mkEnvSeg(before, Curve.lin) // EnvSegment.Obj.ApplySingle()
      inputParent.updateChild(before = before, now = nowVar, dt = durFrames, clearRight = true )
      inputParent.updateChild(before = before, now = seg   , dt = 0L       , clearRight = false)
    }
  }
}