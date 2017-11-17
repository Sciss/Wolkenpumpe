/*
 *  NuagesScalarAttrInput.scala
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

import java.awt.Graphics2D

import de.sciss.lucre.expr.Expr
import de.sciss.lucre.synth.Sys
import de.sciss.synth.Curve
import de.sciss.synth.proc.EnvSegment

import scala.collection.immutable.{IndexedSeq => Vec}

trait NuagesScalarAttrInput[S <: Sys[S]] extends NuagesAttrInputImpl[S] {
  // ---- abstract ----

  protected def toDouble  (in: A     ): Double
  protected def fromDouble(in: Double): A

  // ---- impl ----

  final def numericValue: Vec[Double] = Vector(toDouble(valueA))

  protected final def mkConst(v: Vec[Double])(implicit tx: S#Tx): Ex[S] with Expr.Const[S, A] = {
    require(v.size == 1)
    tpe.newConst(fromDouble(v(0)))
  }

  protected final def mkEnvSeg(start: Ex[S], curve: Curve)(implicit tx: S#Tx): EnvSegment.Obj[S] = {
    val lvl = toDouble(start.value)
    EnvSegment.Obj.newVar[S](EnvSegment.Single(lvl, curve))
  }

  final def numChannels = 1

  final def numChildren(implicit tx: S#Tx): Int = 1

  import NuagesDataImpl.{gLine, setSpine}

  protected final def renderValueUpdated(): Unit = renderValueUpdated1(toDouble(renderedValue))

  protected final def valueText(v: Vec[Double]): String = valueText1(v.head)

  protected final def drawAdjust(g: Graphics2D, v: Vec[Double]): Unit = {
    setSpine(v.head)
    g.draw(gLine)
  }
}