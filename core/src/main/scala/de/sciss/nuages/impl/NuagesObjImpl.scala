/*
 *  NuagesObjImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2020 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import java.awt.event.MouseEvent
import java.awt.geom.{Arc2D, Area, Point2D}
import java.awt.{Color, Graphics2D}

import de.sciss.dsp.FastLog
import de.sciss.intensitypalette.IntensityPalette
import de.sciss.lucre.{Disposable, DoubleVector, Ident, Obj, Source, SpanLikeObj, TxnLike, Txn => LTxn}
import de.sciss.lucre.swing.LucreSwing.{deferTx, requireEDT}
import de.sciss.lucre.synth.Txn
import de.sciss.nuages.Nuages.Surface
import de.sciss.span.{Span, SpanLike}
import de.sciss.synth.proc.Implicits._
import de.sciss.synth.proc.{AuralObj, ObjKeys, Proc}
import prefuse.util.ColorLib
import prefuse.visual.{AggregateItem, VisualItem}

import scala.concurrent.stm.{Ref, TMap}
import scala.swing.event.Key

object NuagesObjImpl {
  private val logPeakCorr = 20.0f

  private val colrPeak = Array.tabulate(91)(ang => new Color(IntensityPalette.apply(ang / 90f)))

  def apply[T <: Txn[T]](main: NuagesPanel[T], locOption: Option[Point2D], id: Ident[T], obj: Obj[T],
                         spanValue: SpanLike, spanOption: Option[SpanLikeObj[T]], hasMeter: Boolean, hasSolo: Boolean)
                        (implicit tx: T, context: NuagesContext[T]): NuagesObj[T] = {
    val frameOffset = spanValue match {
      case hs: Span.HasStart  => hs.start
      case _                  => Long.MaxValue
    }
    val res = new NuagesObjImpl(main, obj.name, frameOffset = frameOffset, hasMeter = hasMeter, hasSolo = hasSolo)
    res.init(id, obj, spanOption, locOption)
  }

  private val fastLog = FastLog(base = 10, q = 11)

  private[this] val ignoredKeys = Set(ObjKeys.attrName, Nuages.attrShortcut, "track-index", "track-height")

  private def isAttrShown(key: String): Boolean =
    !ignoredKeys.contains(key) && !key.endsWith(ParamSpec.DashKey) && !key.startsWith("$")
}
final class NuagesObjImpl[T <: Txn[T]] private(val main: NuagesPanel[T],
                                               var name: String,
                                               val frameOffset: Long,
                                               hasMeter: Boolean, hasSolo: Boolean)(implicit context: NuagesContext[T])
  extends NuagesNodeRootImpl[T] with NuagesObj[T] {
  vProc =>

  import NuagesDataImpl._
  import NuagesObjImpl._
  import LTxn.peer

  protected def nodeSize = 1f

  private[this] var observers = List.empty[Disposable[T]]

  private[this] var _aggr: AggregateItem = _

  private[this] val _outputs = TMap.empty[String, NuagesOutput   [T]]
  private[this] val _attrs   = TMap.empty[String, NuagesAttribute[T]]

  private[this] var idH         : Source[T, Ident[T]]                    = _
  private[this] var objH        : Source[T, Obj[T]]                  = _
  private[this] var spanOptionH : Option[Source[T, SpanLikeObj[T]]]  = _


  private[this] val playArea = new Area()
  private[this] val soloArea = new Area()

  private[this] var peak        = 0f
  private[this] var peakToPaint = -160f
  private[this] var peakNorm    = 0f

  private[this] var lastUpdate = System.currentTimeMillis()

  private[this] var _soloed     = false

  private[this] val auralRef    = Ref(Option.empty[AuralObj.Proc[T]])

  override def toString = s"NuagesObj($name)@${hashCode.toHexString}"

  def parent: NuagesObj[T] = this

  def aggregate: AggregateItem = _aggr

  override def outputs   (implicit tx: T): Map[String, NuagesOutput   [T]] = _outputs .snapshot
  override def attributes(implicit tx: T): Map[String, NuagesAttribute[T]] = _attrs   .snapshot

  def id        (implicit tx: T): Ident[T]                   = idH()
  def obj       (implicit tx: T): Obj[T]                 = objH()
  def spanOption(implicit tx: T): Option[SpanLikeObj[T]] = spanOptionH.map(_.apply())

  def init(id: Ident[T], obj: Obj[T], spanOption: Option[SpanLikeObj[T]], locOption: Option[Point2D])
          (implicit tx: T): this.type = {
    idH         = tx.newHandle(id)
    objH        = tx.newHandle(obj)
    spanOptionH = spanOption.map(tx.newHandle(_))
    main.registerNode(id, this) // nodeMap.put(id, this)
    main.deferVisTx(initGUI(locOption))
    obj match {
      case proc: Proc[T] => initProc(proc)
      case _ =>
    }
    this
  }

  def isCollector(implicit tx: TxnLike): Boolean =
    _outputs.isEmpty && name.startsWith("O-") // attrs.contains("in") && attrs.size == 1

  def hasOutput(key: String)(implicit tx: TxnLike): Boolean                 = _outputs.contains(key)
  def getOutput(key: String)(implicit tx: TxnLike): Option[NuagesOutput[T]] = _outputs.get(key)

  def setSolo(onOff: Boolean)(implicit tx: T): Unit = {
    for {
      outputView <- _outputs.get(Proc.mainOut)
    } {
      outputView.setSolo(onOff = onOff)
    }
    deferTx {
      _soloed = onOff
    }
  }

  private def initProc(proc: Proc[T])(implicit tx: T): Unit = {
    proc.outputs.iterator.foreach(outputAdded)

    observers ::= proc.changed.react { implicit tx => upd =>
      upd.changes.foreach {
        case Proc.OutputAdded  (output) => outputAdded  (output)
        case Proc.OutputRemoved(output) => outputRemoved(output)
        case _ =>
      }
    }

    val attr = proc.attr
    attr.iterator.foreach { case (key, obj) => attrAdded(key, obj) }
    observers ::= attr.changed.react { implicit tx => upd =>
      upd.changes.foreach {
        case Obj.AttrAdded   (key, obj)         => attrAdded   (key, obj)
        case Obj.AttrRemoved (key, _  )         => attrRemoved (key)
        case Obj.AttrReplaced(key, before, now) => attrReplaced(key, before = before, now = now)
      }
    }
  }

  private def attrAdded(key: String, value: Obj[T])(implicit tx: T): Unit =
    if (isAttrShown(key)) {
      val view = NuagesAttribute(key = key, value = value, parent = parent)
      val res  = _attrs.put(key, view)
      auralRef().foreach(view.auralObjAdded)
      assert(res.isEmpty)
    }

  private def attrRemoved(key: String)(implicit tx: T): Unit =
    if (isAttrShown(key)) {
      val view = _attrs.remove(key).getOrElse(throw new IllegalStateException(s"No view for attribute $key"))
      view.dispose()
    }

  private def attrReplaced(key: String, before: Obj[T], now: Obj[T])(implicit tx: T): Unit =
    if (isAttrShown(key)) {
      val oldView = _attrs.get(key).getOrElse(throw new IllegalStateException(s"No view for attribute $key"))
      if (oldView.tryConsume(newOffset = frameOffset, newValue = now)) return

      oldView.tryReplace(now).fold[Unit] {
        attrRemoved(key)
        attrAdded(key, value = now)
      } { newView =>
        _attrs.put(key, newView)
      }
    }

  private def outputAdded(output: Proc.Output[T])(implicit tx: T): Unit = {
    val view = NuagesOutput(this, output, meter = hasMeter && output.key == Proc.mainOut)
    _outputs.put(output.key, view)
    auralRef().foreach(view.auralObjAdded)
  }

  private def outputRemoved(output: Proc.Output[T])(implicit tx: T): Unit = {
    val view = _outputs.remove(output.key)
      .getOrElse(throw new IllegalStateException(s"View for output ${output.key} not found"))
    view.dispose()
  }

  private def initGUI(locOption: Option[Point2D]): Unit = {
    requireEDT()
    // important: this must be this first step
    _aggr = main.aggregateTable.addItem().asInstanceOf[AggregateItem]
    val vi = mkPNode()
    locOption.foreach { pt =>
      // println(s"location: $pt")
      vi.setEndX(pt.getX)
      vi.setEndY(pt.getY)
    }
  }

//  def meterSynth(implicit tx: T): Option[Synth] = _meterSynth()
//  def meterSynth_=(value: Option[Synth])(implicit tx: T): Unit = {
//    val old = _meterSynth.swap(value)
//    old.foreach(_.dispose())
//  }

  def dispose()(implicit tx: T): Unit = {
    observers.foreach(_.dispose())
//    meterSynth = None
    _attrs .foreach(_._2.dispose())
    // inputs .foreach(_._2.dispose())
    _outputs.foreach(_._2.dispose())
    main.unregisterNode(idH(), this) // nodeMap.remove(idH())
    main.deferVisTx(disposeGUI())
  }

  override def disposeGUI(): Unit = {
    super.disposeGUI()
    main.aggregateTable.removeTuple(aggregate)
    //    main.graph    .removeNode (pNode)
  }

  private def paintToNorm(paint: Float): Float = {
    if (paint >= -30f) {
      if (paint >= -20f) {
        math.min(1f, paint * 0.025f + 1.0f) // 50 ... 100 %
      } else {
        paint * 0.02f + 0.9f // 30 ... 50 %
      }
    } else if (paint >= -50f) {
      if (paint >= -40f) {
        paint * 0.015f + 0.75f // 15 ... 30 %
      } else {
        paint * 0.01f + 0.55f // 5 ... 15%
      }
    } else if (paint >= -60f) {
      paint * 0.005f + 0.3f // 0 ... 5 %
    } else -1f
  }

  def meterUpdate(newPeak0: Double): Unit = {
    val time = System.currentTimeMillis()
    val newPeak = fastLog.calc(math.min(10.0f, newPeak0.toFloat)).toFloat * logPeakCorr
    if (newPeak >= peak) {
      peak = newPeak
    } else {
      // 20 dB in 1500 ms bzw. 40 dB in 2500 ms
      peak = math.max(newPeak, peak - (time - lastUpdate) * (if (peak > -20f) 0.013333333333333f else 0.016f))
    }
    peakToPaint = math.max(peakToPaint, peak)
    peakNorm    = paintToNorm(peakToPaint)

    lastUpdate  = time
  }

  def auralObjAdded  (aural: AuralObj[T])(implicit tx: T): Unit = aural match {
    case ap: AuralObj.Proc[T] =>
      _outputs.foreach(_._2.auralObjAdded  (ap))
      _attrs  .foreach(_._2.auralObjAdded  (ap))
      auralRef() = Some(ap)

    case _ =>
  }

  def auralObjRemoved(aural: AuralObj[T])(implicit tx: T): Unit = aural match {
    case ap: AuralObj.Proc[T] =>
      _outputs.foreach(_._2.auralObjRemoved(ap))
      _attrs  .foreach(_._2.auralObjRemoved(ap))
      auralRef() = None
    case _ =>
  }

  override def removeSelf()(implicit tx: T): Unit = {
    // ---- connect former input sources to former output sinks ----
    // - in the previous version we limit ourselves to
    //  `Proc.mainIn` and `Proc.mainOut`.

    var updatedMain = false // XXX TODO -- horrible, dirty hack

    for {
      outputView <- _outputs.get(Proc.mainOut)
      inputAttr  <- _attrs  .get(Proc.mainIn )
      sourceView <- inputAttr.collect {
        case out: NuagesOutput.Input[T] => out
      }
      sinkView   <- outputView.mappings
    } {
      // println(s"For re-connection we found: ${it.mkString(", ")}")
      val parent = sinkView.inputParent
      val child  = sourceView.output
      if (sinkView.attribute.isControl) {
        val before = obj
        parent.updateChild(before, child, dt = 0L, clearRight = true)
        updatedMain = true
      } else {
        parent.addChild(child)
      }
      // main.addCollectionAttribute(parent = ..., key = ..., child = ...)
    }

    // ---- disconnect outputs ----
    // - we leave the inputs untouched because
    //   they are seen from self and thus will
    //   disappear visually and aurally. if
    //   an offline edit extends the self span,
    //   those connections will automatically extend
    //   likewise.
    _outputs.foreach { case (key, outputView) =>
      val output = outputView.output
      outputView.mappings.foreach { outAttrIn =>
        // XXX TODO -- if we are the last child,
        // we should determine whether the sink
        // is a parameter or filter input. In the
        // latter case, ok, let it go back to zero,
        // in the former case, set the last reported
        // value as scalar.
        // AAA
        // println(s"inputParent = ${outAttrIn.inputParent}")
        val inAttr = outAttrIn.attribute
        if (inAttr.isControl) {
          // skip if we already re-wired our own input
          if (key != Proc.mainOut || !updatedMain) {
            val now = inAttr match {
              case num: NuagesAttribute.Numeric =>
                requireEDT()
                DoubleVector.newVar[T](num.numericValue)

              case _ =>
                println(s"Warning: no numeric attribute input for $inAttr")
                val numCh = 2   // XXX TODO
                DoubleVector.newVar[T](Vector.fill(numCh)(0.0))
            }
            outAttrIn.inputParent.updateChild(output, now, dt = 0L, clearRight = true)
          }
        } else {
          outAttrIn.inputParent.removeChild(output)
        }
      }
    }

    // ---- remove proc ----
    val _obj = objH()
    main.nuages.surface match {
      // XXX TODO --- DRY - NuagesTimelineAttrInput#removeChild
      case Surface.Timeline(tl) =>
        val oldSpan     = spanOption
          .getOrElse(throw new IllegalStateException(s"Using a timeline nuages but no span!?"))
        val pos         = main.transport.position
        val stop        = pos // `- parent.frameOffset` (not, because parent = this)
      val oldSpanVal  = oldSpan.value
        val newSpanVal  = oldSpanVal.intersect(Span.until(stop))
        if (newSpanVal.nonEmpty) {
          oldSpan match {
            case SpanLikeObj.Var(vr) => vr() = newSpanVal
            case _ =>
              val newSpan = SpanLikeObj.newVar[T](newSpanVal)
              val ok = tl.remove(oldSpan, _obj)
              require(ok)
              tl.add(newSpan, _obj)
          }
        } else {
          val ok = tl.remove(oldSpan, _obj)
          require(ok)
        }

      case Surface.Folder(f) =>
        val ok = f.remove(_obj)
        require(ok)
      // ...

      case _ =>
    }

    // XXX TODO --- remove orphaned input or output procs
  }

  override def itemPressed(vi: VisualItem, e: MouseEvent, pt: Point2D): Boolean = {
    // if (!isAlive) return false
    if (super.itemPressed(vi, e, pt)) return true
    // if (isSynthetic) return false

    val xt = pt.getX - r.getX
    val yt = pt.getY - r.getY
    if (playArea.contains(xt, yt)) {
      true
    } else if (hasSolo && soloArea.contains(xt, yt)) {
      val newState = !_soloed
      atomic { implicit tx =>
        main.setSolo(this, newState)
      }
      true

    } else if (outerShape.contains(xt, yt) & e.isAltDown) {
      txRemoveSelf()
      true

    } else false
  }

  private def txRemoveSelf(): Unit =
    atomic { implicit tx =>
      removeSelf()
    }

  override def itemKeyPressed(vi: VisualItem, e: KeyControl.Pressed): Boolean = {
    val code = e.code
    if (code == Key.BackSpace /*&& outerShape.contains(xt, yt)*/) { // copy
      txRemoveSelf()
      true

    } else {
      super.itemKeyPressed(vi, e)
    }
  }

  protected def boundsResized(): Unit = {
    // val arc = new Arc2D.Double(0, 0, r.getWidth, r.getHeight, 135, 90, Arc2D.PIE)
    gArc.setArc(0, 0, r.getWidth, r.getHeight, 135, 90, Arc2D.PIE)
    playArea.reset()
    playArea.add(new Area(gArc))
    playArea.subtract(new Area(innerShape))
    gp.append(playArea, false)

    if (hasSolo) {
      gArc.setAngleStart(45)
      soloArea.reset()
      soloArea.add(new Area(gArc))
      soloArea.subtract(new Area(innerShape))
      gp.append(soloArea, false)
    }

    if (hasMeter) {
      gArc.setAngleStart(-45)
      val meterArea = new Area(gArc)
      meterArea.subtract(new Area(innerShape))
      gp.append(meterArea, false)
    }
  }

  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit = {
    g.setColor(colrPlaying)
    g.fill(playArea)

    if (hasSolo) {
      g.setColor(if (_soloed) colrSoloed else colrStopped)
      g.fill(soloArea)
    }

    if (hasMeter) {
      val angExtent = (math.max(0f, peakNorm) * 90).toInt
      gArc.setArc(0, 0, r.getWidth, r.getHeight, -45, angExtent, Arc2D.PIE)
      val peakArea  = new Area(gArc)
      peakArea.subtract(new Area(innerShape))

      g.setColor(colrPeak(angExtent))
      g.fill(peakArea)
      peakToPaint = -160f
      //      rmsToPaint	= -160f
    }

    // if (vi.isHighlighted) println(s"HIGHLIGHT ${name}")
    // g.setColor(if (vi.isHighlighted) Color.yellow else Color.white)
    g.setColor(ColorLib.getColor(vi.getStrokeColor))
    g.draw(gp)

    drawName(g, vi, diam * vi.getSize.toFloat * 0.33333f)
  }
}