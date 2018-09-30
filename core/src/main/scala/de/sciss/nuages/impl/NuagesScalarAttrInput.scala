/*
 *  NuagesScalarAttrInput.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2018 Hanns Holger Rutz. All rights reserved.
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

import de.sciss.lucre.synth.Sys
import de.sciss.synth.Curve
import de.sciss.synth.proc.EnvSegment

import scala.collection.immutable.{IndexedSeq => Vec}

trait NuagesScalarAttrInput[S <: Sys[S]] extends NuagesAttrInputExprImpl[S] {
  // ---- abstract ----

  protected def toDouble  (in: A     ): Double
  protected def fromDouble(in: Double): A

  // ---- impl ----

  final def numericValue: Vec[Double] = Vector(toDouble(valueA))

  protected final def mkConst(v: Vec[Double])(implicit tx: S#Tx): Repr[S] = {
    require(v.size == 1)
    tpe.newConst(fromDouble(v(0)))
  }

  protected final def mkEnvSeg(start: Repr[S], curve: Curve)(implicit tx: S#Tx): EnvSegment.Obj[S] = {
    val lvl = toDouble(start.value)
    EnvSegment.Obj.newVar[S](EnvSegment.Single(lvl, curve))
  }

  final def numChannels = 1

  import NuagesDataImpl.{gLine, setSpine}

  protected final def renderValueUpdated(): Unit = renderValueUpdated1(toDouble(renderedValue))

  protected final def valueText(v: Vec[Double]): String = valueText1(v.head)

  protected final def drawAdjust(g: Graphics2D, v: Vec[Double]): Unit = {
    setSpine(v.head)
    g.draw(gLine)
  }
}