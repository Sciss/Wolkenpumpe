/*
 *  NuagesDataImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2019 Hanns Holger Rutz. All rights reserved.
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
import java.awt.geom.{AffineTransform, Arc2D, Ellipse2D, GeneralPath, Line2D, Point2D, Rectangle2D}
import java.awt.{BasicStroke, Color, Font, Graphics2D, Shape}
import java.math.{MathContext, RoundingMode}

import de.sciss.lucre.synth.Sys
import prefuse.util.ColorLib
import prefuse.visual.VisualItem

import scala.concurrent.stm.Txn

object NuagesDataImpl {
  final val diam    = 50
  final val diam05  = 25 // diam * 0.5

  private final val eps = 1.0e-2

  final val colrPlaying   = new Color(0x00, 0xC0, 0x00)
  final val colrStopped   = new Color(0x80, 0x80, 0x80)
  final val colrBypassed  = new Color(0xFF, 0xC0, 0x00)
  final val colrSoloed    = new Color(0xFF, 0xFF, 0x00)
  final val colrMapped    = new Color(210,  60,  60)
  final val colrManual    = new Color( 60,  60, 240)
  final val colrGliding   = new Color(135,  60, 150)
  final val colrAdjust    = new Color(0xFF, 0xC0, 0x00)

  final val strkThick     = new BasicStroke(2f)
  final val strkVeryThick = new BasicStroke(4f)
  final val strkDotted    = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, Array(1f, 1f), 0f)

  final val gArc          = new Arc2D    .Double
  final val gLine         = new Line2D   .Double
  final val gEllipse      = new Ellipse2D.Double

  final val margin  : Double = diam   * 0.2
  final val margin2 : Double = margin * 2

  final val threeDigits   = new MathContext(3, RoundingMode.HALF_UP)

  /** Changes `gLine`. */
  def setSpine(v: Double): Unit = {
    val ang   = ((1.0 - v) * 1.5 - 0.25) * math.Pi
    val cos   = math.cos(ang)
    val sin   = math.sin(ang)
    val x0    = (1 + cos) * diam05
    val y0    = (1 - sin) * diam05
    gLine.setLine(x0, y0, x0 - (cos * diam * 0.2), y0 + (sin * diam * 0.2))
  }
}
trait NuagesDataImpl[S <: Sys[S]] extends NuagesData[S] {
  import NuagesDataImpl._

  // ---- abstract ----

  protected def main: NuagesPanel[S]

  def name: String

  protected def boundsResized(): Unit

  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit

  // ---- impl: state ----

  protected final val r: Rectangle2D = new Rectangle2D.Double()
  protected final val gp        = new GeneralPath()

  private[this] var lastFontT : AffineTransform = _
  private[this] var lastLabel : String          = _
  private[this] var labelShape: Shape           = _
  private[this] var _fontSize : Float           = 0f
  private[this] var _font     : Font            = _
  private[this] val _outerE   : Ellipse2D       = new Ellipse2D.Double()
  private[this] val _innerE   : Ellipse2D       = new Ellipse2D.Double()
  private[this] var _outline  : Shape           = r

  final var fixed = false

  // ---- impl: methods ----
  
  protected final def innerShape: Shape = _innerE
  protected final def outerShape: Shape = _outerE
  final def outline             : Shape = _outline
  
  def copyFrom(that: NuagesData[S]): Unit = {
    require(Txn.findCurrent.isEmpty)
    update(that.outline)
    fixed = that.fixed
  }

  def update(shp: Shape): Unit = {
    val newR = shp.getBounds2D
    if ((math.abs(newR.getWidth  - r.getWidth ) < eps) &&
        (math.abs(newR.getHeight - r.getHeight) < eps)) {

      r.setFrame(newR.getX, newR.getY, r.getWidth, r.getHeight)
      return
    }
    r.setFrame(newR)
    _outline = shp

    _outerE.setFrame(     0,      0, r.getWidth          , r.getHeight          )
    _innerE.setFrame(margin, margin, r.getWidth - margin2, r.getHeight - margin2)
    gp.reset()
    gp.append(_outerE, false)
    boundsResized()
  }

  def render(g: Graphics2D, vi: VisualItem): Unit = {
    // fixed nodes are indicated by a think white _outline
    if (fixed) {
      val strkOrig = g.getStroke
      g.setStroke(strkVeryThick)
      g.setColor(ColorLib.getColor(vi.getStrokeColor))
      g.draw(_outline)
      g.setStroke(strkOrig)
    }
    g.setColor(ColorLib.getColor(vi.getFillColor))
    g.fill(_outline)
    val atOrig = g.getTransform
    g.translate(r.getX, r.getY)
    //         g.setRenderingHint( RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON )
    renderDetail(g, vi)
    g.setTransform(atOrig)
  }

  override def itemEntered (vi: VisualItem, e: MouseEvent, pt: Point2D): Unit    = ()
  override def itemExited  (vi: VisualItem, e: MouseEvent, pt: Point2D): Unit    = ()
  override def itemPressed (vi: VisualItem, e: MouseEvent, pt: Point2D): Boolean = false
  override def itemReleased(vi: VisualItem, e: MouseEvent, pt: Point2D): Unit    = ()
  override def itemDragged (vi: VisualItem, e: MouseEvent, pt: Point2D): Unit    = ()

  override def itemKeyPressed (vi: VisualItem, e: KeyControl.Pressed): Boolean  = false
  override def itemKeyReleased(vi: VisualItem, e: KeyControl.Pressed): Unit     = ()
  override def itemKeyTyped   (vi: VisualItem, e: KeyControl.Typed  ): Unit     = ()

  protected def drawName(g: Graphics2D, vi: VisualItem, fontSize: Float): Unit =
    drawLabel(g, vi, fontSize, name)
  
  protected def drawLabel(g: Graphics2D, vi: VisualItem, fontSize: Float, text: String): Unit = {
    if (_fontSize != fontSize) {
      _fontSize = fontSize
      _font     = Wolkenpumpe.condensedFont.deriveFont(fontSize)
    }

    g.setColor(ColorLib.getColor(vi.getTextColor))

    if (main.display.isHighQuality) {
      val frc   = g.getFontRenderContext
      val frcT  = frc.getTransform
      if (frcT != lastFontT || text != lastLabel) {  // only calculate glyph vector if zoom level changes
        val v = _font.createGlyphVector(frc, text)
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
        labelShape = v.getOutline(((r.getWidth  - vvb.getWidth ) * 0.5).toFloat,
                                  ((r.getHeight + vvb.getHeight) * 0.5).toFloat)
        lastFontT = frcT
        lastLabel = text
      }
      g.fill(labelShape)

    } else {
      val cx = r.getWidth  / 2
      val cy = r.getHeight / 2
      val fm = g.getFontMetrics
      g.drawString(text, (cx -  (fm.stringWidth(text)          * 0.5)).toInt,
                         (cy + ((fm.getAscent - fm.getLeading) * 0.5)).toInt)
    }
  }
}
