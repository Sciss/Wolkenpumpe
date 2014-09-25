/*
 *  VisualData.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2013 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either
 *  version 2, june 1991 of the License, or (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public
 *  License (gpl.txt) along with this software; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import java.awt.{Font, Graphics2D, Shape, BasicStroke, Color}
import java.awt.event.MouseEvent
import de.sciss.intensitypalette.IntensityPalette
import de.sciss.lucre.stm
import de.sciss.lucre.synth.Sys
import prefuse.util.ColorLib
import prefuse.data.{Edge, Node => PNode}
import prefuse.visual.{AggregateItem, VisualItem}
import java.awt.geom.{Arc2D, Area, Point2D, Ellipse2D, GeneralPath, Rectangle2D}
import de.sciss.synth.proc.{Obj, Proc}

private[nuages] object VisualData {
  val diam = 50
  private val eps = 1.0e-2

  val colrPlaying   = new Color(0x00, 0xC0, 0x00)
  val colrStopped   = new Color(0x80, 0x80, 0x80)
  val colrBypassed  = new Color(0xFF, 0xC0, 0x00)
  val colrSoloed    = new Color(0xFF, 0xFF, 0x00)
  val colrMapped    = new Color(210, 60, 60)
  val colrManual    = new Color(60, 60, 240)
  val colrGliding   = new Color(135, 60, 150)
  val colrAdjust    = new Color(0xFF, 0xC0, 0x00)

  val strkThick     = new BasicStroke(2f)

  //      val stateColors   = Array( colrStopped, colrPlaying, colrStopped, colrBypassed )
}

private[nuages] trait VisualData[S <: Sys[S]] {

  import VisualData._

  //      var valid   = false // needs validation first!

  protected val r: Rectangle2D = new Rectangle2D.Double()
  protected var outline: Shape = r
  protected val outerE = new Ellipse2D.Double()
  protected val innerE = new Ellipse2D.Double()
  protected val margin = diam * 0.2
  protected val margin2 = margin * 2
  protected val gp = new GeneralPath()

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

  def render(g: Graphics2D, vi: VisualItem): Unit = {
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

  protected def drawName(g: Graphics2D, vi: VisualItem, font: Font): Unit = {
    g.setColor(ColorLib.getColor(vi.getTextColor))
    if (main.display.isHighQuality) {
      drawNameHQ(g, vi, font)
    } else {
      val cx = r.getWidth / 2
      val cy = r.getHeight / 2
      g.setFont(font)
      val fm = g.getFontMetrics
      val n = name
      g.drawString(n, (cx - (fm.stringWidth(n) * 0.5)).toInt,
        (cy + ((fm.getAscent - fm.getLeading) * 0.5)).toInt)
    }
  }

  private def drawNameHQ(g: Graphics2D, vi: VisualItem, font: Font): Unit = {
    val n = name
    val v = font.createGlyphVector(g.getFontRenderContext, n)
    val vvb = v.getVisualBounds
    //      val vlb = v.getLogicalBounds

    // for PDF output, drawGlyphVector gives correct font rendering,
    // while drawString only does with particular fonts.
    //         g.drawGlyphVector( v, ((r.getWidth() - vb.getWidth()) * 0.5).toFloat,
    //                           ((r.getHeight() + (fm.getAscent() - fm.getLeading())) * 0.5).toFloat )
    //         g.drawGlyphVector( v, ((r.getWidth() - vb.getWidth()) * 0.5).toFloat,
    //                               ((r.getHeight() - vb.getHeight()) * 0.5).toFloat )
    val shp = v.getOutline(((r.getWidth - vvb.getWidth) * 0.5).toFloat,
      ((r.getHeight + vvb.getHeight) * 0.5).toFloat)
    g.fill(shp)
  }

  def name: String

  protected def boundsResized(): Unit

  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit
}

object VisualProc {
  private val logPeakCorr = 20.0 / math.log(10)
  //   private val logRMSCorr		= 10.0 / math.log( 10 )

//   private def hsbFade( w1: Float, rgbMin: Int, rgbMax: Int ) : Color = {
//      val hsbTop = new Array[ Float ]( 3 )
//      val hsbBot = new Array[ Float ]( 3 )
//      Color.RGBtoHSB( (rgbMax >> 16) & 0xFF, (rgbMax >> 8) & 0xFF, rgbMax & 0xFF, hsbTop )
//      Color.RGBtoHSB( (rgbMin >> 16) & 0xFF, (rgbMin >> 8) & 0xFF, rgbMin & 0xFF, hsbBot );
//      val w2 = 1f - w1
//      Color.getHSBColor( hsbTop( 0 ) * w1 + hsbBot( 0 ) * w2,
//           hsbTop( 1 ) * w1 + hsbBot( 1 ) * w2,
//           hsbTop( 2 ) * w1 + hsbBot( 2 ) * w2 )
//   }
//
   // private val colrPeak       = Array.tabulate( 91 )( ang => hsbFade( ang / 91f, 0x02FF02, 0xFF6B6B ))
   private val colrPeak = Array.tabulate(91)(ang => new Color(IntensityPalette.apply(ang / 90f)))

  def apply[S <: Sys[S]](main: NuagesPanel[S], obj: Obj[S], pMeter: Option[stm.Source[S#Tx, Proc.Obj[S]]],
                         meter: Boolean, solo: Boolean)
                        (implicit tx: S#Tx): VisualProc[S] =
    new VisualProc(main, tx.newHandle(obj), pMeter, meter = meter, solo = solo)
}

private[nuages] class VisualProc[S <: Sys[S]] private (val main: NuagesPanel[S], val objH: stm.Source[S#Tx, Obj[S]],
                                                       /* val params: Map[String, VisualParam[S]], */
                                                       val pMeter: Option[stm.Source[S#Tx, Proc.Obj[S]]],
                                                       val meter: Boolean, val solo: Boolean)
  extends VisualData[S] {
  vproc =>

  import VisualData._
  import VisualProc._

  var pNode : PNode = _
  var aggr  : AggregateItem = _

  //  @volatile private var stateVar = Proc.State(false)
  @volatile private var disposeAfterFade = false

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

  // def name: String = proc.name

  def name = "TODO: name"

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

   def meterUpdate( newPeak0: Float /*, newRMS0: Float */ ): Unit = {
      val time = System.currentTimeMillis
      val newPeak = (math.log( newPeak0 ) * logPeakCorr).toFloat
      if( newPeak >= peak ) {
         peak = newPeak
      } else {
         // 20 dB in 1500 ms bzw. 40 dB in 2500 ms
         peak = math.max( newPeak, peak - (time - lastUpdate) * (if( peak > -20f ) 0.013333333333333f else 0.016f) )
      }
      peakToPaint	= math.max( peakToPaint, peak )
      peakNorm 	= paintToNorm( peakToPaint )

//      val newRMS = (math.log( newRMS0 ) * logRMSCorr).toFloat
//      if( newRMS > rms ) {
//         rms	= newRMS
//      } else {
//         rms = math.max( newRMS, rms - (time - lastUpdate) * (if( rms > -20f ) 0.013333333333333f else 0.016f) )
//      }
//      rmsToPaint	= Math.max( rmsToPaint, rms )
//      rmsNorm		= paintToNorm( rmsToPaint )

      lastUpdate		= time

//      result		= peakNorm >= 0f;
   }

  //  override def itemPressed(vi: VisualItem, e: MouseEvent, pt: Point2D): Boolean = {
  //    if (!isAlive) return false
  //    if (super.itemPressed(vi, e, pt)) return true
  //    if (isSynthetic) return false
  //
  //    val xt = pt.getX - r.getX
  //    val yt = pt.getY - r.getY
  //    if (playArea.contains(xt, yt)) {
  //      ProcTxn.atomic { implicit t =>
  //        t.withTransition(main.transition(t.time)) {
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
  //      true
  //    } else if (solo && soloArea.contains(xt, yt)) {
  //      main.setSolo(vproc, !soloed)
  //      true
  //      //         println( "SOLO" )
  //    } else if (outerE.contains(xt, yt) & e.isAltDown) {
  //      val instant = !stateVar.playing || stateVar.bypassed || (main.transition(0) == Instant)
  //      proc.anatomy match {
  //        case ProcFilter => {
  //          ProcTxn.atomic { implicit t =>
  //            if (isCollectorInput) {
  //              disposeCollectorInput(proc)
  //            } else {
  //              if (instant) disposeFilter
  //              else {
  //                t.afterCommit { _ => disposeAfterFade = true}
  //                t.withTransition(main.transition(t.time)) {
  //                  proc.bypass
  //                }
  //              }
  //            }
  //          }
  //        }
  //        case ProcDiff => {
  //          ProcTxn.atomic { implicit t =>
  //            //                     val toDispose = MSet.empty[ VisualProc ]()
  //            //                     addToDisposal( toDispose, vproc )
  //
  //            if (instant) disposeGenDiff
  //            else {
  //              t.afterCommit { _ => disposeAfterFade = true}
  //              t.withTransition(main.transition(t.time)) {
  //                proc.stop
  //              }
  //            }
  //          }
  //        }
  //        case _ =>
  //      }
  //      true
  //    } else false
  //  }

  //  /*
  //   * Removes and disposes subtree (without fading)
  //   */
  //  private def disposeSubTree(p: Proc)(implicit tx: ProcTxn): Unit = {
  //    p.audioOutputs.flatMap(_.edges).foreach(e => e.out ~/> e.in)
  //    val srcs: Set[Proc] = p.audioInputs.flatMap(_.edges).map(e => {
  //      val pSrc = e.sourceVertex
  //      if (pSrc.isPlaying) pSrc.stop
  //      e.out ~/> e.in
  //      pSrc
  //    })(collection.breakOut)
  //    p.dispose
  //    srcs.foreach(disposeSubTree(_))
  //  }

  //  private def disposeCollectorInput(p: Proc)(implicit tx: ProcTxn): Unit = {
  //    val trans = main.transition(tx.time) match {
  //      case XFade(start, dur) => Glide(start, dur)
  //      case n => n
  //    }
  //
  //    val instant = !stateVar.playing || stateVar.bypassed || trans == Instant
  //
  //    def dispo(implicit tx: ProcTxn): Unit = disposeSubTree(p)
  //
  //    if (!instant) {
  //      tx.withTransition(trans) {
  //        val ctrl = p.control("amp")
  //        ctrl.v = ctrl.spec.lo
  //      }
  //      ProcessHelper.whenGlideDone(p, "amp")(dispo(_))
  //    } else {
  //      dispo
  //    }
  //  }

  //  private def disposeProc(): Unit = {
  //    //println( "DISPOSE " + proc )
  //    ProcTxn.atomic { implicit t =>
  //      proc.anatomy match {
  //        case ProcFilter => disposeFilter
  //        case _ => disposeGenDiff
  //      }
  //    }
  //  }

  //  private def disposeFilter(implicit tx: ProcTxn): Unit = {
  //    val in = proc.audioInput("in")
  //    val out = proc.audioOutput("out")
  //    val ines = in.edges.toSeq
  //    val outes = out.edges.toSeq
  //    if (ines.size > 1) println("WARNING : Filter is connected to more than one input!")
  //    outes.foreach(oute => out ~/> oute.in)
  //    ines.headOption.foreach(ine => {
  //      outes.foreach(oute => ine.out ~> oute.in)
  //    })
  //    // XXX tricky: this needs to be last, so that
  //    // the pred out's bus isn't set to physical out
  //    // (which is currently not undone by AudioBusImpl)
  //    ines.foreach(ine => ine.out ~/> in)
  //    proc.dispose
  //  }

  //  private def disposeGenDiff(implicit tx: ProcTxn): Unit = {
  //    var toDispose = ISet.empty[Proc]
  //    def addToDisposal(p: Proc): Unit = {
  //         if( toDispose.contains( p )) return
  //      toDispose += p
  //      val more = p.audioInputs.flatMap(_.edges.map(_.sourceVertex)) ++
  //        p.audioOutputs.flatMap(_.edges.map(_.targetVertex))
  //      more.foreach(addToDisposal _) // XXX tail rec
  //    }
  //    addToDisposal(proc)
  //    toDispose.foreach(p => {
  //      val ines = p.audioInputs.flatMap(_.edges).toSeq // XXX
  //      val outes = p.audioOutputs.flatMap(_.edges).toSeq // XXX
  //      outes.foreach(oute => oute.out ~/> oute.in)
  //      ines.foreach(ine => ine.out ~/> ine.in)
  //      p.dispose
  //    })
  //  }

  protected def boundsResized(): Unit = {
    val arc = new Arc2D.Double(0, 0, r.getWidth, r.getHeight, 135, 90, Arc2D.PIE)
    playArea.reset()
    playArea.add(new Area(arc))
    playArea.subtract(new Area(innerE))
    gp.append(playArea, false)

    if (solo) {
      arc.setAngleStart(45)
      soloArea.reset()
      soloArea.add(new Area(arc))
      soloArea.subtract(new Area(innerE))
      gp.append(soloArea, false)
    }

    if (meter) {
      arc.setAngleStart(-45)
      val meterArea = new Area(arc)
      meterArea.subtract(new Area(innerE))
      gp.append(meterArea, false)
    }
  }

    protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit = {
      if (true /* stateVar.valid */) {
        g.setColor(if (true /* stateVar.playing */) {
          /* if (stateVar.bypassed) colrBypassed else */ colrPlaying
        } else colrStopped
        )
        g.fill(playArea)

        if (solo) {
          g.setColor(if (soloed) colrSoloed else colrStopped)
          g.fill(soloArea)
        }

        if (meter) {
          val angExtent = (math.max(0f, peakNorm) * 90).toInt
          val pValArc = new Arc2D.Double(0, 0, r.getWidth, r.getHeight, -45, angExtent, Arc2D.PIE)
          val peakArea = new Area(pValArc)
          peakArea.subtract(new Area(innerE))

          g.setColor(colrPeak(angExtent))
          g.fill(peakArea)
          peakToPaint = -160f
          //      rmsToPaint	= -160f
        }
      }
      //         g.setColor( ColorLib.getColor( vi.getStrokeColor ))
      g.setColor(if (disposeAfterFade) Color.red else Color.white)
      g.draw(gp)

      val font = Wolkenpumpe.condensedFont.deriveFont(diam * vi.getSize.toFloat * 0.33333f)
      drawName(g, vi, font)
    }
}

private[nuages] trait VisualParam[S <: Sys[S]] extends VisualData[S] {
  //      def param: ProcParam[ _ ]
  def pNode: PNode

  def pEdge: Edge
}

//private[nuages] trait VisualBusParam extends VisualParam {
//  def bus: ProcAudioBus
//}

//private[nuages] case class VisualAudioInput(main: NuagesPanel, bus: ProcAudioInput, pNode: PNode, pEdge: Edge)
//  extends VisualBusParam {
//
//  import VisualData._
//
//  def name: String = bus.name
//
//  protected def boundsResized() = ()
//
//  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit = {
//    val font = Wolkenpumpe.condensedFont.deriveFont(diam * vi.getSize.toFloat * 0.5f)
//    drawName(g, vi, font)
//  }
//}

//private[nuages] case class VisualAudioOutput(main: NuagesPanel, bus: ProcAudioOutput, pNode: PNode, pEdge: Edge)
//  extends VisualBusParam {
//
//  import VisualData._
//
//  def name: String = bus.name
//
//  protected def boundsResized() = ()
//
//  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit = {
//    val font = Wolkenpumpe.condensedFont.deriveFont(diam * vi.getSize.toFloat * 0.5f)
//    drawName(g, vi, font)
//  }
//}

//private[nuages] final case class VisualMapping(mapping: ControlBusMapping, pEdge: Edge)

  //private[nuages] final case class VisualControl(control: ProcControl, pNode: PNode, pEdge: Edge)(vProc: => VisualProc)
  //  extends VisualParam {
  //
  //  import VisualData._
  //
  //  var value: ControlValue = null
  //  //      var mapped  = false
  //  var gliding = false
  //  var mapping: Option[VisualMapping] = None
  //
  //  private var renderedValue = Double.NaN
  //  private val containerArea = new Area()
  //  private val valueArea = new Area()
  //
  //  def name: String = control.name
  //
  //  def main: NuagesPanel = vProc.main
  //
  //  private var drag: Option[Drag] = None
  //
  //  //      private def isValid = vProc.isValid
  //
  //  override def itemPressed(vi: VisualItem, e: MouseEvent, pt: Point2D): Boolean = {
  //    if (!vProc.isAlive) return false
  //    //         if( super.itemPressed( vi, e, pt )) return true
  //
  //    if (containerArea.contains(pt.getX - r.getX, pt.getY - r.getY)) {
  //      val dy = r.getCenterY - pt.getY
  //      val dx = pt.getX - r.getCenterX
  //      val ang = math.max(0.0, math.min(1.0, (((-math.atan2(dy, dx) / math.Pi + 3.5) % 2.0) - 0.25) / 1.5))
  //      val instant = !vProc.state.playing || vProc.state.bypassed || main.transition(0) == Instant
  //      val vStart = if (e.isAltDown) {
  //        //               val res = math.min( 1.0f, (((ang / math.Pi + 3.25) % 2.0) / 1.5).toFloat )
  //        //               if( ang != value ) {
  //        val m = control.spec.map(ang)
  //        if (instant) setControl(control, m, true)
  //        //               }
  //        ang
  //      } else control.spec.unmap(value.currentApprox)
  //      val dr = Drag(ang, vStart, instant)
  //      drag = Some(dr)
  //      true
  //    } else false
  //  }
  //
  //  private def setControl(c: ProcControl, v: Double, instant: Boolean): Unit =
  //    ProcTxn.atomic { implicit t =>
  //      if (instant) {
  //        c.v = v
  //      } else t.withTransition(main.transition(t.time)) {
  //        c.v = v
  //      }
  //    }
  //
  //  override def itemDragged(vi: VisualItem, e: MouseEvent, pt: Point2D): Unit =
  //    drag.foreach { dr =>
  //      val dy = r.getCenterY - pt.getY
  //      val dx = pt.getX - r.getCenterX
  //      //            val ang  = -math.atan2( dy, dx )
  //      val ang = (((-math.atan2(dy, dx) / math.Pi + 3.5) % 2.0) - 0.25) / 1.5
  //      val vEff = math.max(0.0, math.min(1.0, dr.valueStart + (ang - dr.angStart)))
  //      //            if( vEff != value ) {
  //      val m = control.spec.map(vEff)
  //      if (dr.instant) {
  //        setControl(control, m, true)
  //      } else {
  //        dr.dragValue = m
  //      }
  //      //            }
  //    }
  //
  //  override def itemReleased(vi: VisualItem, e: MouseEvent, pt: Point2D): Unit =
  //    drag foreach { dr =>
  //      if (!dr.instant) {
  //        setControl(control, dr.dragValue, false)
  //      }
  //      drag = None
  //    }
  //
  //  protected def boundsResized(): Unit = {
  //    val pContArc = new Arc2D.Double(0, 0, r.getWidth, r.getHeight, -45, 270, Arc2D.PIE)
  //    containerArea.reset()
  //    containerArea.add(new Area(pContArc))
  //    containerArea.subtract(new Area(innerE))
  //    gp.append(containerArea, false)
  //    renderedValue = Double.NaN // triggers updateRenderValue
  //  }
  //
  //  private def updateRenderValue(v: Double): Unit = {
  //    renderedValue = v
  //    val vn = control.spec.unmap(v)
  //    //println( "updateRenderValue( " + control.name + " ) from " + v + " to " + vn )
  //    val angExtent = (vn * 270).toInt
  //    val angStart = 225 - angExtent
  //    val pValArc = new Arc2D.Double(0, 0, r.getWidth, r.getHeight, angStart, angExtent, Arc2D.PIE)
  //    valueArea.reset()
  //    valueArea.add(new Area(pValArc))
  //    valueArea.subtract(new Area(innerE))
  //  }
  //
  //  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit = {
  //    if (vProc.state.valid) {
  //      val v = value.currentApprox
  //      if (renderedValue != v) {
  //        updateRenderValue(v)
  //      }
  //      g.setColor(if (mapping.isDefined) colrMapped else if (gliding) colrGliding else colrManual)
  //      g.fill(valueArea)
  //      drag foreach { dr => if (!dr.instant) {
  //        g.setColor(colrAdjust)
  //        val ang = ((1.0 - control.spec.unmap(dr.dragValue)) * 1.5 - 0.25) * math.Pi
  //        //               val cx   = diam * 0.5 // r.getCenterX
  //        //               val cy   = diam * 0.5 // r.getCenterY
  //        val cos = math.cos(ang)
  //        val sin = math.sin(ang)
  //        //               val lin  = new Line2D.Double( cx + cos * diam*0.5, cy - sin * diam*0.5,
  //        //                                             cx + cos * diam*0.3, cy - sin * diam*0.3 )
  //        val diam05 = diam * 0.5
  //        val x0 = (1 + cos) * diam05
  //        val y0 = (1 - sin) * diam05
  //        val lin = new Line2D.Double(x0, y0,
  //          x0 - (cos * diam * 0.2), y0 + (sin * diam * 0.2))
  //        val strkOrig = g.getStroke
  //        g.setStroke(strkThick)
  //        g.draw(lin)
  //        g.setStroke(strkOrig)
  //      }
  //      }
  //    }
  //    g.setColor(ColorLib.getColor(vi.getStrokeColor))
  //    g.draw(gp)
  //
  //    val font = Wolkenpumpe.condensedFont.deriveFont(diam * vi.getSize.toFloat * 0.33333f)
  //    drawName(g, vi, font)
  //  }
  //
  //  private final case class Drag(angStart: Double, valueStart: Double, instant: Boolean) {
  //    var dragValue = valueStart
  //  }
  // }