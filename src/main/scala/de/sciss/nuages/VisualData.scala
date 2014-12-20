/*
 *  VisualData.scala
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

import java.awt.{Font, Graphics2D, Shape, BasicStroke, Color}
import java.awt.event.MouseEvent
import de.sciss.intensitypalette.IntensityPalette
import de.sciss.lucre.expr.{Expr, Double => DoubleEx}
import de.sciss.lucre.bitemp.{SpanLike => SpanLikeEx}
import de.sciss.lucre.stm
import de.sciss.lucre.stm.Disposable
import de.sciss.lucre.synth.{Synth, Sys}
import de.sciss.span.SpanLike
import de.sciss.synth.proc
import prefuse.util.ColorLib
import prefuse.data.{Edge, Node => PNode}
import prefuse.visual.{AggregateItem, VisualItem}
import java.awt.geom.{AffineTransform, Line2D, Arc2D, Area, Point2D, Ellipse2D, GeneralPath, Rectangle2D}
import de.sciss.synth.proc.{Scan, DoubleElem, Obj, Proc}
import scala.collection.breakOut
import proc.Implicits._

import scala.concurrent.stm.Ref

private[nuages] object VisualData {
  final val diam    = 50
  final val diam05  = 25 // diam * 0.5

  private final val eps = 1.0e-2

  final val colrPlaying   = new Color(0x00, 0xC0, 0x00)
  final val colrStopped   = new Color(0x80, 0x80, 0x80)
  final val colrBypassed  = new Color(0xFF, 0xC0, 0x00)
  final val colrSoloed    = new Color(0xFF, 0xFF, 0x00)
  final val colrMapped    = new Color(210, 60, 60)
  final val colrManual    = new Color(60, 60, 240)
  final val colrGliding   = new Color(135, 60, 150)
  final val colrAdjust    = new Color(0xFF, 0xC0, 0x00)

  final val strkThick     = new BasicStroke(2f)
  final val strkVeryThick = new BasicStroke(4f)

  final val gArc          = new Arc2D.Double
  final val gLine         = new Line2D.Double

  final val margin        = diam * 0.2
  final val margin2       = margin * 2
}

private[nuages] trait VisualData[S <: Sys[S]] {

  import VisualData._

  //      var valid   = false // needs validation first!

  protected val r: Rectangle2D = new Rectangle2D.Double()
  protected var outline: Shape = r
  protected val outerE  = new Ellipse2D.Double()
  protected val innerE  = new Ellipse2D.Double()
  protected val gp      = new GeneralPath()

  var fixed = false

  protected def main: NuagesPanel[S]

  def update(shp: Shape): Unit = {
    val newR = shp.getBounds2D
    if ((math.abs(newR.getWidth  - r.getWidth ) < eps) &&
        (math.abs(newR.getHeight - r.getHeight) < eps)) {

      r.setFrame(newR.getX, newR.getY, r.getWidth, r.getHeight)
      return
    }
    r.setFrame(newR)
    outline = shp

    outerE.setFrame(0, 0, r.getWidth, r.getHeight)
    innerE.setFrame(margin, margin, r.getWidth - margin2, r.getHeight - margin2)
    gp.reset()
    gp.append(outerE, false)
    boundsResized()
  }

  private var _fontSize = 0f
  private var _font: Font = _

  def render(g: Graphics2D, vi: VisualItem): Unit = {
    if (fixed) {
      val strkOrig = g.getStroke
      g.setStroke(strkVeryThick)
      g.setColor(ColorLib.getColor(vi.getStrokeColor))
      g.draw(outline)
      g.setStroke(strkOrig)
    }
    g.setColor(ColorLib.getColor(vi.getFillColor))
    g.fill(outline)
    val atOrig = g.getTransform
    g.translate(r.getX, r.getY)
    //         g.setRenderingHint( RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON )
    renderDetail(g, vi)
    g.setTransform(atOrig)
  }

  def itemEntered (vi: VisualItem, e: MouseEvent, pt: Point2D) = ()
  def itemExited  (vi: VisualItem, e: MouseEvent, pt: Point2D) = ()
  def itemPressed (vi: VisualItem, e: MouseEvent, pt: Point2D) = false
  def itemReleased(vi: VisualItem, e: MouseEvent, pt: Point2D) = ()
  def itemDragged (vi: VisualItem, e: MouseEvent, pt: Point2D) = ()

  private[this] var lastFontT: AffineTransform = _
  private[this] var nameShape: Shape = _

  protected def drawName(g: Graphics2D, vi: VisualItem, fontSize: Float): Unit = {
    if (_fontSize != fontSize) {
      _fontSize = fontSize
      _font = Wolkenpumpe.condensedFont.deriveFont(fontSize)
    }

    g.setColor(ColorLib.getColor(vi.getTextColor))
    val n = name

    if (main.display.isHighQuality) {
      val frc   = g.getFontRenderContext
      val frcT  = frc.getTransform
      if (frcT != lastFontT) {  // only calculate glyph vector if zoom level changes
        val v = _font.createGlyphVector(frc, n)
        // NOTE: there is a bug, at least with the BellySansCondensed font,
        // regarding `getVisualBounds`; it returns almost infinite width
        // for certain strings such as `"freq"`. Instead, using `getPixelBounds`
        // seems to resolve the issue.
        //
        // val vvb = v.getVisualBounds

        // NOTE: the getPixelBounds somehow incorporates wrong zoom factors.
        // The problem with `getVisualBounds` seems to originate from the
        // initial font-render-context.
        val vvb = if (frc.isTransformed) v.getVisualBounds else v.getPixelBounds(frc, 0f, 0f)

        // if (name == "freq") println(s"w = ${vvb.getWidth}, h = ${vvb.getHeight}; t? ${frc.isTransformed}")

        // for PDF output, drawGlyphVector gives correct font rendering,
        // while drawString only does with particular fonts.
        //         g.drawGlyphVector( v, ((r.getWidth() - vb.getWidth()) * 0.5).toFloat,
        //                           ((r.getHeight() + (fm.getAscent() - fm.getLeading())) * 0.5).toFloat )
        //         g.drawGlyphVector( v, ((r.getWidth() - vb.getWidth()) * 0.5).toFloat,
        //                               ((r.getHeight() - vb.getHeight()) * 0.5).toFloat )
        nameShape = v.getOutline(((r.getWidth - vvb.getWidth) * 0.5).toFloat,
          ((r.getHeight + vvb.getHeight) * 0.5).toFloat)
        lastFontT = frcT
      }
      g.fill(nameShape)

    } else {
      val cx = r.getWidth  / 2
      val cy = r.getHeight / 2
      val fm = g.getFontMetrics
      g.drawString(n, (cx - (fm.stringWidth(n) * 0.5)).toInt, (cy + ((fm.getAscent - fm.getLeading) * 0.5)).toInt)
    }
  }

  def name: String

  protected def boundsResized(): Unit

  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit
}

object VisualObj {
  private val logPeakCorr = 20.0 / math.log(10)

  private val colrPeak = Array.tabulate(91)(ang => new Color(IntensityPalette.apply(ang / 90f)))

  def apply[S <: Sys[S]](main: NuagesPanel[S], span: Expr[S, SpanLike], obj: Obj[S],
                         meter: Boolean, solo: Boolean)
                        (implicit tx: S#Tx): VisualObj[S] = {
    import SpanLikeEx.serializer
    val res = new VisualObj(main, tx.newHandle(span), tx.newHandle(obj), obj.name, meter = meter, solo = solo)
    obj match {
      case Proc.Obj(objT) =>
        val scans = objT.elem.peer.scans
        res.scans = scans.keys.map { key => key -> new VisualScan(res, key) } (breakOut)
        res.params = objT.attr.iterator.flatMap {
          case (key, DoubleElem.Obj(dObj)) =>
            val vc = VisualControl.scalar(res, key, dObj)
            Some(key -> vc)
          case (key, Scan.Obj(sObj)) =>
            val vc = VisualControl.scan(res, key, sObj)
            Some(key -> vc)
          case _ =>
            None
        } .toMap

      case _ =>
    }
    res
  }
}

private[nuages] trait VisualNode[S <: Sys[S]] extends VisualData[S] {
  var pNode: PNode = _

  final protected def atomic[A](fun: S#Tx => A): A = main.transport.scheduler.cursor.step(fun)
}

private[nuages] class VisualObj[S <: Sys[S]] private (val main: NuagesPanel[S],
                                                      val spanH: stm.Source[S#Tx, Expr[S, SpanLike]],
                                                      val objH: stm.Source[S#Tx, Obj[S]],
                                                      var name: String,
                                                      /* val params: Map[String, VisualParam[S]], */
                                                      val meter: Boolean, val solo: Boolean)
  extends VisualNode[S] with Disposable[S#Tx] {
  vProc =>

  import VisualData._
  import VisualObj._

  var aggr  : AggregateItem = _

  var scans   = Map.empty[String, VisualScan   [S]]
  var params  = Map.empty[String, VisualControl[S]]

  private val _meterSynth = Ref(Option.empty[Synth])

  final def meterSynth(implicit tx: S#Tx): Option[Synth] = _meterSynth.get(tx.peer)
  final def meterSynth_=(value: Option[Synth])(implicit tx: S#Tx): Unit = {
    val old = _meterSynth.swap(value)(tx.peer)
    old.foreach(_.dispose())
  }

  def dispose()(implicit tx: S#Tx): Unit = meterSynth = None

  //  @volatile private var stateVar = Proc.State(false)
  // @volatile private var disposeAfterFade = false

  private val playArea = new Area()
  private val soloArea = new Area()

  private var peak = 0f
  //   private var rms         = 0f
  private var peakToPaint = -160f
  //   private var rmsToPaint	= -160f
  private var peakNorm = 0f
  //   private var rmsNorm     = 0f
  private var lastUpdate = System.currentTimeMillis()

  @volatile var soloed = false

  //  private val isCollectorInput = main.collector.isDefined && (proc.anatomy == ProcFilter) && (proc.name.startsWith("O-"))
  //  private val isSynthetic = proc.name.startsWith("_")

  // def isAlive = stateVar.valid && !disposeAfterFade

  // def state = stateVar

  //  def state_=(value: Proc.State): Unit = {
  //    stateVar = value
  //    if (disposeAfterFade && !value.fading && !isCollectorInput) disposeProc()
  //  }

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

  def meterUpdate(newPeak0: Float /*, newRMS0: Float */): Unit = {
    val time = System.currentTimeMillis
    val newPeak = (math.log(newPeak0) * logPeakCorr).toFloat
    if (newPeak >= peak) {
      peak = newPeak
    } else {
      // 20 dB in 1500 ms bzw. 40 dB in 2500 ms
      peak = math.max(newPeak, peak - (time - lastUpdate) * (if (peak > -20f) 0.013333333333333f else 0.016f))
    }
    peakToPaint = math.max(peakToPaint, peak)
    peakNorm = paintToNorm(peakToPaint)

    //      val newRMS = (math.log( newRMS0 ) * logRMSCorr).toFloat
    //      if( newRMS > rms ) {
    //         rms	= newRMS
    //      } else {
    //         rms = math.max( newRMS, rms - (time - lastUpdate) * (if( rms > -20f ) 0.013333333333333f else 0.016f) )
    //      }
    //      rmsToPaint	= Math.max( rmsToPaint, rms )
    //      rmsNorm		= paintToNorm( rmsToPaint )

    lastUpdate = time

    //      result		= peakNorm >= 0f;
  }

  override def itemPressed(vi: VisualItem, e: MouseEvent, pt: Point2D): Boolean = {
    // if (!isAlive) return false
    if (super.itemPressed(vi, e, pt)) return true
    // if (isSynthetic) return false

    val xt = pt.getX - r.getX
    val yt = pt.getY - r.getY
    if (playArea.contains(xt, yt)) {
//      atomic { implicit tx =>
//        tx.withTransition(main.transition(tx.time)) {
//          if (stateVar.playing) {
//            if (proc.anatomy == ProcFilter && !isCollectorInput) {
//              if (stateVar.bypassed) proc.engage else proc.bypass
//            } else {
//              proc.stop
//            }
//          } else {
//            proc.play
//          }
//        }
//      }
      true
    } else if (solo && soloArea.contains(xt, yt)) {
      main.setSolo(this, !soloed)
      true

    } else if (outerE.contains(xt, yt) & e.isAltDown) {
      // val instant = !stateVar.playing || stateVar.bypassed || (main.transition(0) == Instant)
      val scanKeys = scans.keySet
      val mappings = scans.valuesIterator.flatMap(_.mappings)
      atomic { implicit tx =>
        if (scanKeys.nonEmpty) {
          mappings.foreach { vc =>
            vc.removeMapping()
          }

          val obj = objH()
          obj match {
            case Proc.Obj(objT) =>
              val scans = objT.elem.peer.scans
              val ins   = scans.get("in" ).fold(List.empty[Scan[S]])(_.sources.collect { case Scan.Link.Scan(source) => source } .toList)
              val outs  = scans.get("out").fold(List.empty[Scan[S]])(_.sinks  .collect { case Scan.Link.Scan(sink  ) => sink   } .toList)
              scanKeys.foreach { key =>
                scans.get(key).foreach { scan =>
                  scan.sinks  .toList.foreach(scan.removeSink  )
                  scan.sources.toList.foreach(scan.removeSource)
                }
              }
              ins.foreach { in =>
                outs.foreach { out =>
                  in.addSink(out)
                }
              }

            case _ =>
          }

          main.nuages.timeline.elem.peer.modifiableOption.foreach { tl =>
            // XXX TODO --- ought to be an update to the span variable
            tl.remove(spanH(), obj)
          }
          // XXX TODO --- remove orphaned input or output procs
        }
      }
//      proc.anatomy match {
//        case ProcFilter =>
//          atomic { implicit tx =>
//            if (isCollectorInput) {
//              disposeCollectorInput(proc)
//            } else {
//              if (instant) disposeFilter
//              else {
//                tx.afterCommit { disposeAfterFade = true}
//                tx.withTransition(main.transition(t.time)) {
//                  proc.bypass
//                }
//              }
//            }
//          }
//
//        case ProcDiff =>
//          atomic { implicit tx =>
//            if (instant) disposeGenDiff
//            else {
//              tx.afterCommit { disposeAfterFade = true}
//              tx.withTransition(main.transition(tx.time)) {
//                proc.stop
//              }
//            }
//          }
//
//        case _ =>
//      }
      true

    } else false
  }

  protected def boundsResized(): Unit = {
    // val arc = new Arc2D.Double(0, 0, r.getWidth, r.getHeight, 135, 90, Arc2D.PIE)
    gArc.setArc(0, 0, r.getWidth, r.getHeight, 135, 90, Arc2D.PIE)
    playArea.reset()
    playArea.add(new Area(gArc))
    playArea.subtract(new Area(innerE))
    gp.append(playArea, false)

    if (solo) {
      gArc.setAngleStart(45)
      soloArea.reset()
      soloArea.add(new Area(gArc))
      soloArea.subtract(new Area(innerE))
      gp.append(soloArea, false)
    }

    if (meter) {
      gArc.setAngleStart(-45)
      val meterArea = new Area(gArc)
      meterArea.subtract(new Area(innerE))
      gp.append(meterArea, false)
    }
  }

  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit = {
    g.setColor(colrPlaying)
    g.fill(playArea)

    if (solo) {
      g.setColor(if (soloed) colrSoloed else colrStopped)
      g.fill(soloArea)
    }

    if (meter) {
      val angExtent = (math.max(0f, peakNorm) * 90).toInt
      // val pValArc   = new Arc2D.Double(0, 0, r.getWidth, r.getHeight, -45, angExtent, Arc2D.PIE)
      gArc.setArc(0, 0, r.getWidth, r.getHeight, -45, angExtent, Arc2D.PIE)
      val peakArea  = new Area(gArc)
      peakArea.subtract(new Area(innerE))

      g.setColor(colrPeak(angExtent))
      g.fill(peakArea)
      peakToPaint = -160f
      //      rmsToPaint	= -160f
    }

    g.setColor(/* if (disposeAfterFade) Color.red else */ Color.white)
    g.draw(gp)

    drawName(g, vi, diam * vi.getSize.toFloat * 0.33333f)
  }
}

private[nuages] trait VisualParam[S <: Sys[S]] extends VisualNode[S] {
  var pEdge: Edge  = _

  def parent: VisualObj[S]
  def key: String

  def name: String = key
  def main = parent.main
}

//private[nuages] trait VisualBusParam extends VisualParam {
//  def bus: ProcAudioBus
//}

private[nuages] class VisualScan[S <: Sys[S]](val parent: VisualObj[S], val key: String)
  extends VisualParam[S] /* VisualBusParam */ {

  import VisualData._

  var sources   = Set.empty[Edge]
  var sinks     = Set.empty[Edge]
  var mappings  = Set.empty[VisualControl[S]]

  protected def boundsResized() = ()

  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit =
    drawName(g, vi, diam * vi.getSize.toFloat * 0.5f)
}

private[nuages] final class VisualMapping[S <: Sys[S]] {
  /** The metering synth that via `SendTrig` updates the control's current value. */
  val synth   = Ref(Option.empty[Synth])
  // var pEdge   = Option.empty[Edge]
  var source  = Option.empty[VisualScan[S]]
}

private[nuages] object VisualControl {
  private val defaultSpec = ParamSpec()

  private def getSpec[S <: Sys[S]](parent: VisualObj[S], key: String)(implicit tx: S#Tx): ParamSpec =
    parent.objH().attr[ParamSpec.Elem](s"$key-${ParamSpec.Key}").map(_.value).getOrElse(defaultSpec)

  def scalar[S <: Sys[S]](parent: VisualObj[S], key: String,
                         dObj: DoubleElem.Obj[S])(implicit tx: S#Tx): VisualControl[S] = {
    val value = dObj.elem.peer.value
    val spec  = getSpec(parent, key)
    new VisualControl(parent, key, spec, value, mapping = None)
  }

  def scan[S <: Sys[S]](parent: VisualObj[S], key: String,
                         sObj: Scan.Obj[S])(implicit tx: S#Tx): VisualControl[S] = {
    val value = 0.5 // XXX TODO
    val spec  = getSpec(parent, key)
    new VisualControl(parent, key, spec, value, mapping = Some(new VisualMapping))
  }

  //  def add[S <: Sys[S]](parent: VisualObj[S], key: String,
  //                       dObj: DoubleElem.Obj[S])(implicit tx: S#Tx): VisualControl[S] = {
  //    val res = apply(parent, key, dObj)
  //    deferTx {
  //      visDo {
  //        ...
  //      }
  //    }
  //  }
}
private[nuages] final class VisualControl[S <: Sys[S]] private(val parent: VisualObj[S], val key: String,
                                                       val /* var */ spec: ParamSpec, @volatile var value: Double,
                                                       val mapping: Option[VisualMapping[S]])
  extends VisualParam[S] {

  import VisualData._

  // var value: ControlValue = null
  // var gliding = false

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
      val dr = Drag(ang, vStart, instant)
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

  private final case class Drag(angStart: Double, valueStart: Double, instant: Boolean) {
    var dragValue = valueStart
  }
}