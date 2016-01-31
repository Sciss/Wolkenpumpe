/*
 *  NuagesDoubleVectorAttrInput.scala
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

import java.awt.geom.{Arc2D, Area}

import de.sciss.lucre.expr.Expr.Const
import de.sciss.lucre.expr.{Type, DoubleVector}
import de.sciss.lucre.stm.{Sys, Obj}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.{Input, Parent}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.swing.Graphics2D

object NuagesDoubleVectorAttrInput extends NuagesAttributeSingleFactory {
  def typeID: Int = DoubleVector.typeID

  type Repr[~ <: Sys[~]] = DoubleVector[~]

  def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: Parent[S], frameOffset: Long, obj: DoubleVector[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] =
    new NuagesDoubleVectorAttrInput[S](attr).init(obj, parent)
}
final class NuagesDoubleVectorAttrInput[S <: SSys[S]](val attribute: NuagesAttribute[S])
  extends NuagesAttrInputImpl[S] {

  type A                = Vec[Double]
  type Ex[~ <: Sys[~]]  = DoubleVector[~]

  def tpe: Type.Expr[A, Ex] = DoubleVector

  private[this] var allValuesEqual = false

  protected def mkConst(v: Vec[Double])(implicit tx: S#Tx): DoubleVector[S] with Const[S, Vec[Double]] =
    tpe.newConst(v)

  def value: Vec[Double] = valueA

//  def tryMigrate(to: Obj[S])(implicit tx: S#Tx): Boolean = ...

  //  def value_=(v: Vec[Double]): Unit = {
//    //    if (v.size != valueA.size)
//    //      throw new IllegalArgumentException(s"Channel mismatch, expected $numChannels but given ${v.size}")
//    valueA = v
//  }

//  def value1_=(v: Double): Unit = {
//    if (valueA.size != 1) throw new IllegalArgumentException(s"Channel mismatch, expected $numChannels but given 1")
//    valueA = Vector.empty :+ v
//  }

  def numChannels = valueA.size

//  protected def setControlTxn(v: Vec[Double])(implicit tx: S#Tx): Unit = {
////    val attr = parent.obj.attr
//    sourceOpt.foreach { src =>
//      val vc = DoubleVector.newConst[S](v)
//      src().update(vc)
//    }
////    attr.$[DoubleVector](key) match {
////      case Some(DoubleVector.Var(vr)) => vr() = vc
////      case _ => attr.put(key, DoubleVector.newVar(vc))
////    }
//  }

  import NuagesDataImpl.{gArc, gEllipse, gLine}

  protected def renderValueUpdated(): Unit = {
    val rv: Vec[Double] = renderedValue // why IntelliJ !?
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
      val m1 = NuagesDataImpl.margin / sz
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

  protected def valueText(v: Vec[Double]): String =
    if (allValuesEqual) {
      valueText1(v.head)
    } else {
      val s1 = valueText1(v.head)
      val s2 = valueText1(v.last)
      s"$s1…$s2"
    }

  protected def drawAdjust(g: Graphics2D, v: Vec[Double]): Unit =
    if (allValuesEqual || true /* XXX TODO */ ) {
      setSpine(v.head)
      g.draw(gLine)
    } else {
      ???!
      //    setSpine(v.head)
      //    g.draw(gLine)
    }
}