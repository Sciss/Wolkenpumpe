/*
 *  AttrInputKeyControl.scala
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

import java.awt.datatransfer.{Clipboard, ClipboardOwner, Transferable}
import java.awt.geom.Point2D
import java.awt.{Color, Point, Toolkit}
import java.text.NumberFormat
import java.util.Locale

import de.sciss.desktop.KeyStrokes
import de.sciss.equal.Implicits._
import de.sciss.lucre.Txn
import de.sciss.nuages.KeyControl.ControlDrag
import de.sciss.numbers
import javax.swing.event.{AncestorEvent, AncestorListener}
import prefuse.visual.VisualItem

import scala.annotation.switch
import scala.collection.immutable.{IndexedSeq => Vec}
import scala.swing.event.Key
import scala.swing.{Label, Orientation, Swing, TextField}
import scala.util.Try

object AttrInputKeyControl {
  private val decimalFormat: NumberFormat = {
    val nf = NumberFormat.getInstance(Locale.US)
    nf.setGroupingUsed(false)
    nf.setMaximumFractionDigits(3)
    nf
  }
}
trait AttrInputKeyControl[T <: Txn[T]] extends ClipboardOwner {
  self: NuagesAttribute.Input[T] with NuagesAttribute.Numeric with NuagesData[T] =>

  import AttrInputKeyControl._

  // ---- abstract ----

  protected def numChannels: Int

  protected def setControl(v: Vec[Double], dur: Float): Unit

  // ---- impl ----

  protected def escape(): Boolean = false

  override def itemKeyPressed(vi: VisualItem, e: KeyControl.Pressed): Boolean = {
    val code = e.code
    if ((e.modifiers & KeyStrokes.menu1.mask) === KeyStrokes.menu1.mask) {
      if (code === Key.C) { // copy
        val clip = Toolkit.getDefaultToolkit.getSystemClipboard
        val data = new KeyControl.ControlDrag(numericValue, attribute.spec)
        clip.setContents(data, this)
        true
      } else if (code === Key.V) { // paste clipboard
        val clip = Toolkit.getDefaultToolkit.getSystemClipboard
        if ( /* vc.editable && */ clip.isDataFlavorAvailable(KeyControl.ControlFlavor)) {
          val data = clip.getData(KeyControl.ControlFlavor).asInstanceOf[ControlDrag]
          setControl(data.values, dur = 0f) // XXX TODO -- which want to rescale
        }
        true
      } else {
        false
      }

    } else {
      def glide(k0: Key.Value): Boolean = main.acceptGlideTime && {
        import numbers.Implicits._
        val time0 = code.id.linLin(k0.id, k0.id + 9, 0f, 1f)
        // "double clicking" is to randomise
        main.glideTime =
          if ((main.glideTime absDif time0) < 0.05f || (main.glideTimeSource !== NuagesPanel.GLIDE_KEY)) time0 else {
            (time0 + math.random().toFloat.linLin(0f, 1f, -0.1f, 0.1f)).clip(0f, 1f)
          }
        main.glideTimeSource = NuagesPanel.GLIDE_KEY
        true
      }

      code match {
        case Key.Enter =>
          showParamInput(vi)
          true

        case _ if code >= Key.Key0    && code <= Key.Key9    => glide(Key.Key0   )
        case _ if code >= Key.Numpad0 && code <= Key.Numpad9 => glide(Key.Numpad0)

        case Key.Escape => escape()

        case _ => false
      }
    }
  }

  override def itemKeyTyped(vi: VisualItem, e: KeyControl.Typed): Unit = {
    def checkDouble(out: Vec[Double]): Vec[Double] = {
      val name = attribute.name
      val ok = ((name !== "amp") && (name !== "gain")) || e.count > 1
      if (ok) out else Vector.empty
    }

    val v = (e.char: @switch) match {
      case 'r'  => val v = math.random(); checkDouble(Vector.fill(numChannels)(v))
      case 'R'  => checkDouble(Vector.fill(numChannels)(math.random()))
      case 'n'  => Vector.fill(numChannels)(0.0)
      case 'x'  => checkDouble(Vector.fill(numChannels)(1.0))
      case 'c'  => Vector.fill(numChannels)(0.5)
      case '['  =>
        val s  = attribute.spec
        val vs = numericValue
        val vNew = if (s.warp === IntWarp) {
          vs.map(v => s.inverseMap(s.map(v) - 1))
        } else vs.map(_ - 0.005)
        vNew.map(math.max(0.0, _))

      case ']'  =>
        val s  = attribute.spec
        val vs = numericValue
        val vNew = if (s.warp === IntWarp) {
          vs.map(v => s.inverseMap(s.map(v) + 1))
        } else vs.map(_ + 0.005)
        vNew.map(math.min(1.0, _))

      case '{'  =>  // decrease channel spacing
        val vs      = numericValue
        val max     = vs.max
        val min     = vs.min
        val mid     = (min + max) / 2
        val newMin  = math.min(mid, min + 0.0025)
        val newMax  = math.max(mid, max - 0.0025)
        if (newMin === min && newMax === max) Vector.empty else {
          import numbers.Implicits._
          vs.map(_.linLin(min, max, newMin, newMax))
        }

      case '}'  =>  // increase channel spacing
        val vs      = numericValue
        val max     = vs.max
        val min     = vs.min
        val newMin  = math.max(0.0, min - 0.0025)
        val newMax  = math.min(1.0, max + 0.0025)
        if (newMin === min && newMax === max) Vector.empty else {
          import numbers.Implicits._
          if (min === max) { // all equal -- use a random spread
            vs.map(in => (in + math.random().linLin(0.0, 1.0, -0.0025, +0.0025)).clip(0.0, 1.0))
          } else {
            vs.map(_.linLin(min, max, newMin, newMax))
          }
        }

      case _ => Vector.empty
    }
    if (v.nonEmpty) setControl(v, dur = 0f)
  }

  private def showParamInput(vi: VisualItem): Unit = {
    val spec    = attribute.spec
    val v0      = spec.map(numericValue.head)
    val s0      = decimalFormat.format(v0)
    val ggValue = new TextField(s0 /*f"$v0%1.3f"*/, 12)
    Wolkenpumpe.mkBlackWhite(ggValue)
    ggValue.peer.addAncestorListener(new AncestorListener {
      def ancestorRemoved(e: AncestorEvent): Unit = ()
      def ancestorMoved  (e: AncestorEvent): Unit = ()
      def ancestorAdded  (e: AncestorEvent): Unit = ggValue.requestFocus()
    })
    val p = new OverlayPanel {
      contents += new BasicPanel(Orientation.Horizontal) {
        contents += ggValue
        if (spec.unit.nonEmpty) {
          contents += Swing.HStrut(4)
          contents += new Label(spec.unit) {
            foreground = Color.white
          }
        }
      }
      onComplete {
        close()
        Try(ggValue.text.toDouble).toOption.foreach { newValue =>
          val v   = spec.inverseMap(spec.clip(newValue))
          val vs  = Vector.fill(numChannels)(v)
          setControl(vs, dur = 0f)
        }
      }
    }
    main.showOverlayPanel(p, Some(calcPanelPoint(p, vi)))
  }

  @inline
  private def main: NuagesPanel[T] = attribute.parent.main

  private def calcPanelPoint(p: OverlayPanel, vi: VisualItem): Point = {
    val b     = vi.getBounds
    val dim   = p.preferredSize
    val p2d   = main.display.getTransform.transform(new Point2D.Double(b.getCenterX , b.getMaxY), null)
    new Point(p2d.getX.toInt - dim.width/2, p2d.getY.toInt - 12)
  }

  def lostOwnership(clipboard: Clipboard, contents: Transferable): Unit = ()
}