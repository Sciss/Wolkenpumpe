package de.sciss.nuages
package impl

import java.awt.Graphics2D

import de.sciss.lucre.stm.{Obj, Sys}
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.NodeProvider
import de.sciss.synth.proc.Folder
import prefuse.data.{Node => PNode}
import prefuse.visual.VisualItem

import scala.collection.immutable.{IndexedSeq => Vec}

object NuagesFolderAttribute extends NuagesAttribute.Factory {
  def typeID: Int = Folder.typeID

  type Repr[S <: Sys[S]] = Folder[S]

  def apply[S <: SSys[S]](key: String, value: Folder[S], parent: NuagesObj[S], np: NodeProvider[S])
                         (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute[S] = {

    ???
  }
}
final class NuagesFolderAttribute[S <: SSys[S]](val parent: NuagesObj[S], val key: String, val spec: ParamSpec,
                                                val mapping: Option[NuagesAttribute.Mapping[S]],
                                                protected val nodeProvider: NodeProvider[S])
  extends /* NuagesAttributeImpl[S] */ NuagesParamImpl[S] with NuagesAttribute[S] {


  private[this] var _pNode: PNode = _

  def pNode: PNode = {
    if (_pNode == null) throw new IllegalStateException(s"Component $this has no initialized GUI")
    _pNode
  }

//  type A = Any
//
//  protected def init1(obj: Obj[S])(implicit tx: S#Tx): Unit = ???
//
//  protected def drawAdjust(g: Graphics2D, v: Vec[Double]): Unit = ???
//
//  protected def valueText(v: Vec[Double]): String = ???
//
//  protected def setControlTxn(v: Vec[Double])(implicit tx: S#Tx): Unit = ???
//
//  protected def renderValueUpdated(): Unit = ???
//
//  protected var valueA: A = _
//
//  def numChannels: Int = ???
//
  var value: Vec[Double] = _

  def removeMapping()(implicit tx: S#Tx): Unit = ???

  /** Adjusts the control with the given normalized value. */
  def setControl(v: Vec[Double], instant: Boolean): Unit = ???

  def numChannels: Int = ???

  protected def nodeSize: Float = ???

  def dispose()(implicit tx: S#Tx): Unit = ???

  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit = ???

  protected def boundsResized(): Unit = ???
}