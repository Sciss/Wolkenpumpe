/*
 *  RenderAttrDoubleVec.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2021 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import java.awt.geom.{Area, Arc2D}

import de.sciss.lucre.synth.Txn

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.swing.Graphics2D

trait RenderNumericAttr[T <: Txn[T]] extends RenderAttrValue[T] with NuagesAttribute.Numeric {

  private[this] var allValuesEqual = false

  import NuagesDataImpl.{gArc, gEllipse, gLine, margin, setSpine}

  protected final def renderValueUpdated(): Unit = {
    val rv = numericValue // renderedValue
    if (rv == null) return  // XXX TODO --- when does this happen?
    val sz = rv.size
    val eq = sz == 1 || (sz > 1 && {
      val v0  = rv.head
      var ch  = 1
      var res = true
      while (ch < sz && res) {
        res = rv(ch) == v0
        ch += 1
      }
      res
    })
    allValuesEqual = eq

    if (eq) {
      renderValueUpdated1(rv.head)
    } else {
      var ch = 0
      val m1 = margin / sz
      val w  = r.getWidth
      val h  = r.getHeight
      var m2 = 0.0
      valueArea.reset()
      while (ch < sz) {
        val v         = rv(ch)
        val vc        = math.max(0, math.min(1, v))
        val angExtent = (vc * 270).toInt
        val angStart  = 225 - angExtent
        val m3        = m2 + m2
        gArc.setArc(m2, m2, w - m3, h - m3, angStart, angExtent, Arc2D.PIE)
        valueArea.add(new Area(gArc))
        m2           += m1
        val m4        = m2 + m2
        gEllipse.setFrame(m2, m2, w - m4, h - m4)
        valueArea.subtract(new Area(gEllipse))
        ch += 1
      }
    }
  }

  protected final def valueText(v: Vec[Double]): String =
    if (allValuesEqual) {
      valueText1(v.head)
    } else {
      val s1 = valueText1(v.head)
      val s2 = valueText1(v.last)
      s"$s1…$s2"
    }

  protected final def drawAdjust(g: Graphics2D, v: Vec[Double]): Unit = {
//    if (allValuesEqual || true /* XXX TODO */ ) {
      setSpine(v.head)
      g.draw(gLine)
//    } else {
//      ???!
//      //    setSpine(v.head)
//      //    g.draw(gLine)
//    }
  }
}

trait RenderAttrDoubleVec[T <: Txn[T]] extends RenderNumericAttr[T] {
  type A = Vec[Double]

  final def numericValue: Vec[Double] = valueA
}