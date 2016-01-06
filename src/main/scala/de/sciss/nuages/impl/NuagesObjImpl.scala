/*
 *  NuagesObjImpl.scala
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
import java.awt.geom.{Arc2D, Area, Point2D}
import java.awt.{Color, Graphics2D}

import de.sciss.dsp.FastLog
import de.sciss.intensitypalette.IntensityPalette
import de.sciss.lucre.expr.SpanLikeObj
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, Obj, TxnLike}
import de.sciss.lucre.swing.requireEDT
import de.sciss.lucre.synth.{Synth, Sys}
import de.sciss.nuages.Nuages.Surface
import de.sciss.span.Span
import de.sciss.synth.proc.Implicits._
import de.sciss.synth.proc.{ObjKeys, Proc, Timeline}
import prefuse.util.ColorLib
import prefuse.visual.{AggregateItem, VisualItem}

import scala.concurrent.stm.{Ref, TMap}

object NuagesObjImpl {
  private val logPeakCorr = 20.0f // / math.log(10)

  private val colrPeak = Array.tabulate(91)(ang => new Color(IntensityPalette.apply(ang / 90f)))

  def apply[S <: Sys[S]](main: NuagesPanel[S], locOption: Option[Point2D], id: S#ID, obj: Obj[S],
                         spanOption: Option[SpanLikeObj[S]], hasMeter: Boolean, hasSolo: Boolean)
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesObj[S] = {
    val res = new NuagesObjImpl(main, obj.name, hasMeter = hasMeter, hasSolo = hasSolo)
    res.init(id, obj, spanOption, locOption)
  }

  private val fastLog = FastLog(base = 10, q = 11)

  private[this] val specSuffix  = s"-${ParamSpec.Key}"
  private[this] val ignoredKeys = Set(ObjKeys.attrName, Nuages.attrShortcut)

  private def mkParam[S <: Sys[S]](parent: NuagesObj[S], key: String, obj: Obj[S])
                                  (implicit tx: S#Tx, context: NuagesContext[S]): Unit =
    if (!(key.endsWith(specSuffix) || ignoredKeys.contains(key)))
      NuagesAttribute /* .tryApply */ (key = key, value = obj, parent = parent)
}
final class NuagesObjImpl[S <: Sys[S]] private(val main: NuagesPanel[S],
                                               var name: String,
                                               hasMeter: Boolean, hasSolo: Boolean)(implicit context: NuagesContext[S])
  extends NuagesNodeRootImpl[S] with NuagesObj[S] {
  vProc =>

  import NuagesDataImpl._
  import NuagesObjImpl._
  import TxnLike.peer

  protected def nodeSize = 1f

  private[this] var observers = List.empty[Disposable[S#Tx]]

  private[this] var _aggr: AggregateItem = _

  // val inputs  = TMap.empty[String, NuagesOutput   [S]]
  val outputs = TMap.empty[String, NuagesOutput   [S]]
  val params  = TMap.empty[String, NuagesAttribute[S]]

  private[this] val _meterSynth = Ref(Option.empty[Synth])

  override def toString = s"NuagesObj($name)@${hashCode.toHexString}"

  def parent: NuagesObj[S] = this

  def aggr: AggregateItem = _aggr

  def obj       (implicit tx: S#Tx): Obj[S]                 = objH()
  def spanOption(implicit tx: S#Tx): Option[SpanLikeObj[S]] = spanOptionH.map(_.apply())

  private[this] var idH         : stm.Source[S#Tx, S#ID]                    = _
  private[this] var objH        : stm.Source[S#Tx, Obj[S]]                  = _
  private[this] var spanOptionH : Option[stm.Source[S#Tx, SpanLikeObj[S]]]  = _

  def init(id: S#ID, obj: Obj[S], spanOption: Option[SpanLikeObj[S]], locOption: Option[Point2D])
          (implicit tx: S#Tx): this.type = {
    idH         = tx.newHandle(id)
    objH        = tx.newHandle(obj)
    spanOptionH = spanOption.map(tx.newHandle(_))
    main.nodeMap.put(id, this)
    main.deferVisTx(initGUI(locOption))
    obj match {
      case proc: Proc[S] => initProc(proc)
      case _ =>
    }
    this
  }

  private[this] def initProc(proc: Proc[S])(implicit tx: S#Tx): Unit = {
//    proc.inputs .iterator.foreach { case (key, scan) =>
//      VisualScan(this, scan, key, isInput = true )
//    }
    proc.outputs.iterator.foreach { case (key, output) =>
      NuagesOutput(this, output)
    }

    observers ::= proc.changed.react { implicit tx => upd =>
      upd.changes.foreach {
//        case Proc.InputAdded   (key, scan) => VisualScan(this, scan, key, isInput = true )
//        case Proc.InputRemoved (key, scan) => inputs.get(key).foreach(_.dispose())
        case Proc.OutputAdded  (output) => NuagesOutput(this, output)
        case Proc.OutputRemoved(output) => outputs.get(output.key).foreach(_.dispose())
        case _ =>
      }
    }

    val attr = proc.attr
    attr.iterator.foreach { case (key, obj) =>
      mkParam(this, key, obj)
    }
    observers ::= attr.changed.react { implicit tx => upd =>
      upd.changes.foreach {
        case Obj.AttrAdded  (key, obj) => mkParam(this, key, obj)
        case Obj.AttrRemoved(key, obj) => params.get(key).foreach(_.dispose())
        case Obj.AttrReplaced(key, before, now) =>
          ???!
      }
    }
  }

  private[this] def initGUI(locOption: Option[Point2D]): Unit = {
    requireEDT()
    // important: this must be this first step
    _aggr = main.aggrTable.addItem().asInstanceOf[AggregateItem]
    val vi = mkPNode()
    locOption.foreach { pt =>
      vi.setEndX(pt.getX)
      vi.setEndY(pt.getY)
    }
  }

  def meterSynth(implicit tx: S#Tx): Option[Synth] = _meterSynth()
  def meterSynth_=(value: Option[Synth])(implicit tx: S#Tx): Unit = {
    val old = _meterSynth.swap(value)
    old.foreach(_.dispose())
  }

  def dispose()(implicit tx: S#Tx): Unit = {
    observers.foreach(_.dispose())
    meterSynth = None
    params .foreach(_._2.dispose())
    // inputs .foreach(_._2.dispose())
    outputs.foreach(_._2.dispose())
    main.nodeMap.remove(idH())
    main.deferVisTx(disposeGUI())
  }

  override def disposeGUI(): Unit = {
    super.disposeGUI()
    main.aggrTable.removeTuple(aggr)
    //    main.graph    .removeNode (pNode)
  }

  private[this] val playArea = new Area()
  private[this] val soloArea = new Area()

  private[this] var peak = 0f
  private[this] var peakToPaint = -160f
  private[this] var peakNorm    = 0f

  //   private var rms         = 0f
  //   private var rmsToPaint	= -160f
  //   private var rmsNorm     = 0f

  private[this] var lastUpdate = System.currentTimeMillis()

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

  def meterUpdate(newPeak0: Double): Unit = {
    val time = System.currentTimeMillis()
    val newPeak = fastLog.calc(math.min(10.0f, newPeak0.toFloat)) * logPeakCorr
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
      atomic { implicit tx =>
        val visScans  = /* inputs ++ */ outputs
        // val inKeys    = inputs .keySet
        val outKeys   = outputs.keySet
        val mappings  = visScans.valuesIterator.flatMap(_.mappings)

        mappings.foreach { vc =>
          vc.removeMapping()
        }

        val obj = objH()
        obj match {
          case objT: Proc[S] =>
            val proc  = objT
            // SCAN
//            val ins   = proc.inputs .get(Proc.scanMainIn ).fold(List.empty[Scan[S]])(_.iterator.collect {
//              case Scan.Link.Scan(source) => source
//            } .toList)
//            val outs  = proc.outputs.get(Proc.scanMainOut).fold(List.empty[Scan[S]])(_.iterator.collect {
//              case Scan.Link.Scan(sink  ) => sink
//            } .toList)
//            inKeys.foreach { key =>
//              proc.inputs.get(key).foreach { scan =>
//                scan.iterator.foreach(scan.remove)
//              }
//            }
//            outKeys.foreach { key =>
//              proc.outputs.get(key).foreach { scan =>
//                scan.iterator.foreach(scan.remove)
//              }
//            }
//            ins.foreach { in =>
//              outs.foreach { out =>
//                in.add(out)
//              }
//            }

          case _ =>
        }

        main.nuages.surface match {
          case Surface.Timeline(tl: Timeline.Modifiable[S]) =>
            val oldSpan     = spanOption.getOrElse(throw new IllegalStateException(s"Using a timeline nuages but no span!?"))
            val frame       = main.transport.position
            val newSpanVal  = oldSpan.value.intersect(Span.until(frame))
            val _obj        = objH()
            if (newSpanVal.nonEmpty) {
              oldSpan match {
                case SpanLikeObj.Var(vr) => vr() = newSpanVal
                case _ =>
                  val newSpan = SpanLikeObj.newVar[S](newSpanVal)
                  tl.remove(oldSpan, _obj)
                  tl.add   (newSpan, _obj)
              }
            } else {
              tl.remove(oldSpan, _obj)
            }

          case Surface.Folder  (f) =>
            f.remove(objH())
            // ...

          case _ =>
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