package de.sciss.nuages
package impl

import java.awt.Graphics2D

import de.sciss.lucre.synth.Sys

import scala.collection.immutable.{IndexedSeq => Vec}

trait NuagesScalarAttrInput[S <: Sys[S]] extends NuagesAttrInputImpl[S] {
  protected def toDouble(in: A): Double
  protected def fromDouble(in: Double): A

  def value: Vec[Double] = Vector(toDouble(valueA))
  def value_=(v: Vec[Double]): Unit = {
    if (v.size != 1) throw new IllegalArgumentException("Trying to set multi-channel parameter on scalar control")
    valueA = fromDouble(v.head)
  }

  def value1_=(v: Double): Unit = valueA = fromDouble(v)

  def numChannels = 1

  protected def setControlTxn(v: Vec[Double])(implicit tx: S#Tx): Unit = {
    ???
//    if (v.size != 1) throw new IllegalArgumentException("Trying to set multi-channel parameter on scalar control")
//    val attr = parent.obj.attr
//    val vc   = DoubleObj.newConst[S](v.head)
//    attr.$[DoubleObj](key) match {
//      case Some(DoubleObj.Var(vr)) => vr() = vc
//      case _ => attr.put(key, DoubleObj.newVar(vc))
//    }
  }

  import NuagesDataImpl.gLine

  protected def renderValueUpdated(): Unit = renderValueUpdated1(toDouble(renderedValue))

  protected def valueText(v: Vec[Double]): String = valueText1(v.head)

  protected def drawAdjust(g: Graphics2D, v: Vec[Double]): Unit = {
    setSpine(v.head)
    g.draw(gLine)
  }
}