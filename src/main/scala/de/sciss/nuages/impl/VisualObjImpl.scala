/*
 *  VisualObjImpl.scala
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
import java.awt.geom.{Arc2D, Area, Point2D}
import java.awt.{Color, Graphics2D}

import de.sciss.intensitypalette.IntensityPalette
import de.sciss.lucre.expr.{DoubleObj, SpanLikeObj}
import de.sciss.lucre.stm
import de.sciss.lucre.stm.Obj
import de.sciss.lucre.swing.requireEDT
import de.sciss.lucre.synth.{Synth, Sys}
import de.sciss.synth.proc.Implicits._
import de.sciss.synth.proc.{Proc, Scan}
import prefuse.util.ColorLib
import prefuse.visual.{AggregateItem, VisualItem}

import scala.collection.breakOut
import scala.concurrent.stm.Ref

object VisualObjImpl {
  private val logPeakCorr = 20.0 / math.log(10)

  private val colrPeak = Array.tabulate(91)(ang => new Color(IntensityPalette.apply(ang / 90f)))

  def apply[S <: Sys[S]](main: NuagesPanel[S], span: SpanLikeObj[S], obj: Obj[S],
                         hasMeter: Boolean, hasSolo: Boolean)
                        (implicit tx: S#Tx): VisualObj[S] = {
    val res = new VisualObjImpl(main, tx.newHandle(span), tx.newHandle(obj), obj.name, hasMeter = hasMeter, hasSolo = hasSolo)
    main.deferVisTx(res.initGUI())
    obj match {
      case objT: Proc[S] =>
        val proc    = objT
        res.inputs  = proc.inputs .keys.map { key => key -> VisualScan(res, key, isInput = true ) } (breakOut)
        res.outputs = proc.outputs.keys.map { key => key -> VisualScan(res, key, isInput = false) } (breakOut)
        res.params  = objT.attr.iterator.flatMap {
          case (key, dObj: DoubleObj[S]) =>
            val vc = VisualControl.scalar(res, key, dObj)
            Some(key -> vc)
          case (key, sObj: Scan[S]) =>
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
final class VisualObjImpl[S <: Sys[S]] private (val main: NuagesPanel[S],
                                                val spanH: stm.Source[S#Tx, SpanLikeObj[S]],
                                                val objH : stm.Source[S#Tx, Obj[S]],
                                                var name: String,
                                                hasMeter: Boolean, hasSolo: Boolean)
  extends VisualNodeImpl[S] with VisualObj[S] {
  vProc =>

  import VisualDataImpl._
  import VisualObjImpl._

  protected def nodeSize = 1f

  private var _aggr: AggregateItem = _

  var inputs  = Map.empty[String, VisualScan   [S]]
  var outputs = Map.empty[String, VisualScan   [S]]
  var params  = Map.empty[String, VisualControl[S]]

  private val _meterSynth = Ref(Option.empty[Synth])

  def parent: VisualObj[S] = this

  def aggr: AggregateItem = _aggr

  def initGUI(): Unit = {
    requireEDT()
    // important: this must be this first step
    _aggr = main.aggrTable.addItem().asInstanceOf[AggregateItem]
    mkPNode()
  }

  def meterSynth(implicit tx: S#Tx): Option[Synth] = _meterSynth.get(tx.peer)
  def meterSynth_=(value: Option[Synth])(implicit tx: S#Tx): Unit = {
    val old = _meterSynth.swap(value)(tx.peer)
    old.foreach(_.dispose())
  }

  def dispose()(implicit tx: S#Tx): Unit = meterSynth = None

  private val playArea = new Area()
  private val soloArea = new Area()

  private var peak = 0f
  private var peakToPaint = -160f
  private var peakNorm    = 0f

  //   private var rms         = 0f
  //   private var rmsToPaint	= -160f
  //   private var rmsNorm     = 0f

  private var lastUpdate = System.currentTimeMillis()

  @volatile var soloed = false

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
    val newPeak = (math.log(math.min(10f, newPeak0)) * logPeakCorr).toFloat
    if (newPeak >= peak) {
      peak = newPeak
    } else {
      // 20 dB in 1500 ms bzw. 40 dB in 2500 ms
      peak = math.max(newPeak, peak - (time - lastUpdate) * (if (peak > -20f) 0.013333333333333f else 0.016f))
    }
    peakToPaint = math.max(peakToPaint, peak)
    peakNorm = paintToNorm(peakToPaint)

    lastUpdate = time
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
      main.setSolo(this, !soloed)
      true

    } else if (outerE.contains(xt, yt) & e.isAltDown) {
      // val instant = !stateVar.playing || stateVar.bypassed || (main.transition(0) == Instant)
      val visScans  = inputs ++ outputs
      val inKeys    = inputs .keySet
      val outKeys   = outputs.keySet
      val mappings  = visScans.valuesIterator.flatMap(_.mappings)
      atomic { implicit tx =>
        mappings.foreach { vc =>
          vc.removeMapping()
        }

        val obj = objH()
        obj match {
          case objT: Proc[S] =>
            val proc  = objT
            // val scans = proc.scans
            val ins   = proc.inputs .get(Proc.scanMainIn ).fold(List.empty[Scan[S]])(_.iterator.collect { case Scan.Link.Scan(source) => source } .toList)
            val outs  = proc.outputs.get(Proc.scanMainOut).fold(List.empty[Scan[S]])(_.iterator.collect { case Scan.Link.Scan(sink  ) => sink   } .toList)
            inKeys.foreach { key =>
              proc.inputs.get(key).foreach { scan =>
                scan.iterator.foreach(scan.remove)
              }
            }
            outKeys.foreach { key =>
              proc.outputs.get(key).foreach { scan =>
                scan.iterator.foreach(scan.remove)
              }
            }
            ins.foreach { in =>
              outs.foreach { out =>
                in.add(out)
              }
            }

          case _ =>
        }

        main.nuages.timeline.modifiableOption.foreach { tl =>
          // XXX TODO --- ought to be an update to the span variable
          tl.remove(spanH(), obj)
        }
        // XXX TODO --- remove orphaned input or output procs
      }
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

    if (hasSolo) {
      gArc.setAngleStart(45)
      soloArea.reset()
      soloArea.add(new Area(gArc))
      soloArea.subtract(new Area(innerE))
      gp.append(soloArea, false)
    }

    if (hasMeter) {
      gArc.setAngleStart(-45)
      val meterArea = new Area(gArc)
      meterArea.subtract(new Area(innerE))
      gp.append(meterArea, false)
    }
  }

  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit = {
    g.setColor(colrPlaying)
    g.fill(playArea)

    if (hasSolo) {
      g.setColor(if (soloed) colrSoloed else colrStopped)
      g.fill(soloArea)
    }

    if (hasMeter) {
      val angExtent = (math.max(0f, peakNorm) * 90).toInt
      gArc.setArc(0, 0, r.getWidth, r.getHeight, -45, angExtent, Arc2D.PIE)
      val peakArea  = new Area(gArc)
      peakArea.subtract(new Area(innerE))

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
