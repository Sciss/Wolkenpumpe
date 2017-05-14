/*
 *  AttrInputKeyControl.scala
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

import java.awt.datatransfer.{Transferable, Clipboard, ClipboardOwner}
import java.awt.geom.Point2D
import java.awt.{Color, Point, Toolkit}
import javax.swing.event.{AncestorEvent, AncestorListener}

import de.sciss.desktop.KeyStrokes
import de.sciss.lucre.stm.Sys
import de.sciss.nuages.KeyControl.ControlDrag
import de.sciss.numbers
import prefuse.visual.VisualItem

import scala.annotation.switch
import scala.collection.immutable.{IndexedSeq => Vec}
import scala.swing.event.Key
import scala.swing.{Label, Orientation, Swing, TextField}
import scala.util.Try

trait AttrInputKeyControl[S <: Sys[S]] extends ClipboardOwner {
  _: NuagesAttribute.Input[S] with NuagesAttribute.Numeric with NuagesData[S] =>

  // ---- abstract ----

  protected def numChannels: Int

  protected def setControl(v: Vec[Double], instant: Boolean): Unit

  // ---- impl ----

  override def itemKeyPressed(vi: VisualItem, e: KeyControl.Pressed): Unit =
    if ((e.modifiers & KeyStrokes.menu1.mask) == KeyStrokes.menu1.mask) {
      if (e.code == Key.C) {        // copy
        val clip = Toolkit.getDefaultToolkit.getSystemClipboard
        val data = new KeyControl.ControlDrag(numericValue, attribute.spec)
        clip.setContents(data, this)
      } else  if (e.code == Key.V) {  // paste clipboard
        val clip = Toolkit.getDefaultToolkit.getSystemClipboard
        if ( /* vc.editable && */ clip.isDataFlavorAvailable(KeyControl.ControlFlavor)) {
          val data = clip.getData(KeyControl.ControlFlavor).asInstanceOf[ControlDrag]
          setControl(data.values, instant = true) // XXX TODO -- which want to rescale
        }
      }

    } else {
      if (e.code == Key.Enter) showParamInput(vi)
    }

  override def itemKeyTyped(vi: VisualItem, e: KeyControl.Typed): Unit = {
    def checkDouble(out: Vec[Double]): Vec[Double] = {
      val name = attribute.name
      val ok = (name != "amp" && name != "gain") || e.count > 1
      if (ok) out else Vector.empty
    }

    val v = (e.char: @switch) match {
      case 'r'  => val v = math.random; checkDouble(Vector.fill(numChannels)(v))
      case 'R'  => checkDouble(Vector.fill(numChannels)(math.random))
      case 'n'  => Vector.fill(numChannels)(0.0)
      case 'x'  => checkDouble(Vector.fill(numChannels)(1.0))
      case 'c'  => Vector.fill(numChannels)(0.5)
      case '['  =>
        val s  = attribute.spec
        val vs = numericValue
        val vNew = if (s.warp == IntWarp) {
          vs.map(v => s.inverseMap(s.map(v) - 1))
        } else vs.map(_ - 0.005)
        vNew.map(math.max(0.0, _))

      case ']'  =>
        val s  = attribute.spec
        val vs = numericValue
        val vNew = if (s.warp == IntWarp) {
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
        if (newMin == min && newMax == max) Vector.empty else {
          import numbers.Implicits._
          vs.map(_.linlin(min, max, newMin, newMax))
        }

      case '}'  =>  // increase channel spacing
        val vs      = numericValue
        val max     = vs.max
        val min     = vs.min
        val newMin  = math.max(0.0, min - 0.0025)
        val newMax  = math.min(1.0, max + 0.0025)
        if (newMin == min && newMax == max) Vector.empty else {
          import numbers.Implicits._
          if (min == max) { // all equal -- use a random spread
            vs.map(in => (in + math.random.linlin(0.0, 1.0, -0.0025, +0.0025)).clip(0.0, 1.0))
          } else {
            vs.map(_.linlin(min, max, newMin, newMax))
          }
        }

      case _ => Vector.empty
    }
    if (v.nonEmpty) setControl(v, instant = true)
  }

  private[this] def showParamInput(vi: VisualItem): Unit = {
    val p = new OverlayPanel {
      val spec    = attribute.spec
      val ggValue = new TextField(f"${spec.map(numericValue.head)}%1.3f", 12)
      ggValue.background = Color.black
      ggValue.foreground = Color.white
      ggValue.peer.addAncestorListener(new AncestorListener {
        def ancestorRemoved(e: AncestorEvent): Unit = ()
        def ancestorMoved  (e: AncestorEvent): Unit = ()
        def ancestorAdded  (e: AncestorEvent): Unit = ggValue.requestFocus()
      })
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
          setControl(vs, instant = true)
        }
      }
    }
    main.showOverlayPanel(p, Some(calcPanelPoint(p, vi)))
  }

  @inline
  private[this] def main: NuagesPanel[S] = attribute.parent.main

  private[this] def calcPanelPoint(p: OverlayPanel, vi: VisualItem): Point = {
    val b     = vi.getBounds
    val dim   = p.preferredSize
    val p2d   = main.display.getTransform.transform(new Point2D.Double(b.getCenterX , b.getMaxY), null)
    new Point(p2d.getX.toInt - dim.width/2, p2d.getY.toInt - 12)
  }

  def lostOwnership(clipboard: Clipboard, contents: Transferable): Unit = ()
}