/*
 *  NuagesScalarAttrInput.scala
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

import java.awt.Graphics2D

import de.sciss.lucre.synth.Txn
import de.sciss.synth.Curve
import de.sciss.proc.EnvSegment

import scala.collection.immutable.{IndexedSeq => Vec}

trait NuagesScalarAttrInput[T <: Txn[T]] extends NuagesAttrInputExprImpl[T] {
  // ---- abstract ----

  protected def toDouble  (in: A     ): Double
  protected def fromDouble(in: Double): A

  // ---- impl ----

  final def numericValue: Vec[Double] = Vector(toDouble(valueA))

  protected final def mkConst(v: Vec[Double])(implicit tx: T): Repr[T] = {
    require(v.size == 1)
    tpe.newConst(fromDouble(v(0)))
  }

  protected final def mkEnvSeg(start: Repr[T], curve: Curve)(implicit tx: T): EnvSegment.Obj[T] = {
    val lvl = toDouble(start.value)
    EnvSegment.Obj.newVar[T](EnvSegment.Single(lvl, curve))
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