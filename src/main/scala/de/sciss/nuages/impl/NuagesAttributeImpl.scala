/*
 *  NuagesAttributeImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2015 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import java.awt.event.MouseEvent
import java.awt.geom.{Arc2D, Area, GeneralPath, Point2D}
import java.awt.{Graphics2D, Shape}

import de.sciss.lucre.expr.{DoubleVector, BooleanObj, DoubleObj, IntObj}
import de.sciss.lucre.stm.{Disposable, Obj, Sys}
import de.sciss.lucre.swing.requireEDT
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.{Input, Mapping, Factory}
import de.sciss.synth.proc.Folder
import prefuse.data.{Node => PNode}
import prefuse.util.ColorLib
import prefuse.visual.VisualItem

import scala.collection.immutable.{IndexedSeq => Vec}

object NuagesAttributeImpl {
  private[this] final val sync = new AnyRef
  
  def addFactory(f: Factory): Unit = sync.synchronized {
    val tid = f.typeID
    if (map.contains(tid)) throw new IllegalArgumentException(s"View factory for type $tid already installed")
    map += tid -> f
  }

  def factories: Iterable[Factory] = map.values

  def apply[S <: SSys[S]](key: String, value: Obj[S], parent: NuagesObj[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute[S] = {
    val tid     = value.tpe.typeID
    val factory = map.getOrElse(tid,
      throw new IllegalArgumentException(s"No NuagesAttribute available for $value / type 0x${tid.toHexString}"))
    val input   = factory(key = key, value = value.asInstanceOf[factory.Repr[S]], attr = ???)
    ??? // factory(key, value.asInstanceOf[factory.Repr[S]], parent)
  }

  def tryApply[S <: SSys[S]](key: String, value: Obj[S], parent: NuagesObj[S])
                           (implicit tx: S#Tx, context: NuagesContext[S]): Option[NuagesAttribute[S]] = {
    val tid = value.tpe.typeID
    val opt = map.get(tid)
    ??? // opt.map(f => f(key, value.asInstanceOf[f.Repr[S]], parent))
  }

  private[this] var map = Map[Int, Factory](
    IntObj              .typeID -> NuagesIntAttrInput,
    DoubleObj           .typeID -> NuagesDoubleAttrInput,
    BooleanObj          .typeID -> NuagesBooleanAttrInput,
//    FadeSpec.Obj        .typeID -> FadeSpecAttribute,
    DoubleVector        .typeID -> NuagesDoubleVectorAttrInput,
//    Grapheme.Expr.Audio .typeID -> AudioGraphemeAttribute,
//    Output              .typeID -> NuagesOutputAttribute,
    Folder              .typeID -> NuagesFolderAttribute
//    Timeline            .typeID -> NuagesTimelineAttribute
  )
  
  // ----
  
  private val defaultSpec = ParamSpec()

  def getSpec[S <: Sys[S]](parent: NuagesObj[S], key: String)(implicit tx: S#Tx): ParamSpec =
    parent.obj.attr.$[ParamSpec.Obj](s"$key-${ParamSpec.Key}").map(_.value).getOrElse(defaultSpec)

//  private final val scanValue = Vector(0.5): Vec[Double] // XXX TODO

  private final class Impl[S <: SSys[S]](val parent: NuagesObj[S], val key: String, val spec: ParamSpec,
                                         input: NuagesAttribute.Input[S])
    extends NuagesParamImpl[S] with NuagesAttribute[S] {

    def numChannels: Int = input.numChannels

    var value: Vec[Double] = ???

    def addPNode(in: Input[S], n: PNode, isFree: Boolean): Unit = {
      ???
    }

    def removePNode(in: Input[S], n: PNode): Unit = {
      ???
    }

    def pNode: PNode = ???

    def mapping: Option[Mapping[S]] = ???

    def removeMapping()(implicit tx: S#Tx): Unit = ???

    /** Adjusts the control with the given normalized value. */
    def setControl(v: Vec[Double], instant: Boolean): Unit = ???

    protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit = ???

    protected def boundsResized(): Unit = ???

    def dispose()(implicit tx: S#Tx): Unit = ???
  }
}