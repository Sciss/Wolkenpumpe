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

import java.awt.Graphics2D

import de.sciss.lucre.expr.{BooleanObj, DoubleObj, DoubleVector, IntObj}
import de.sciss.lucre.stm.{TxnLike, Obj, Sys}
import de.sciss.lucre.swing.requireEDT
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.{Factory, Input}
import de.sciss.synth.proc.{Timeline, Output, Folder}
import prefuse.data.{Node => PNode, Edge => PEdge}
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

//  def apply[S <: SSys[S]](key: String, value: Obj[S], parent: NuagesObj[S])
//                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute[S] =
//    tryApply(key, value, parent).getOrElse {
//      val tid = value.tpe.typeID
//      throw new IllegalArgumentException(s"No NuagesAttribute available for $key / $value / type 0x${tid.toHexString}")
//    }

  def apply[S <: SSys[S]](key: String, _value: Obj[S], parent: NuagesObj[S])
                         (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute[S] = {
    import TxnLike.peer
    val spec = getSpec(parent, key)
    val res = new Impl[S](parent = parent, key = key, spec = spec) { self =>
      protected val input = mkInput(self, _value)
    }
    parent.params.put(key, res)
    res
  }

  def mkInput[S <: SSys[S]](attr: NuagesAttribute[S], value: Obj[S])
                           (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] = {
    val tid = value.tpe.typeID
    val opt = map.get(tid)
    val factory = opt.getOrElse {
      val msg = s"No NuagesAttribute available for ${attr.key} / $value / type 0x${tid.toHexString}"
      throw new IllegalArgumentException(msg)
    }
    factory[S](value = value.asInstanceOf[factory.Repr[S]], attr = attr)
  }

  private[this] var map = Map[Int, Factory](
    IntObj              .typeID -> NuagesIntAttrInput,
    DoubleObj           .typeID -> NuagesDoubleAttrInput,
    BooleanObj          .typeID -> NuagesBooleanAttrInput,
//    FadeSpec.Obj        .typeID -> FadeSpecAttribute,
    DoubleVector        .typeID -> NuagesDoubleVectorAttrInput,
//    Grapheme.Expr.Audio .typeID -> AudioGraphemeAttribute,
    Output              .typeID -> NuagesOutputAttrInput,
    Folder              .typeID -> NuagesFolderAttrInput,
    Timeline            .typeID -> NuagesTimelineAttrInput
  )
  
  // ----
  
  private val defaultSpec = ParamSpec()

  def getSpec[S <: Sys[S]](parent: NuagesObj[S], key: String)(implicit tx: S#Tx): ParamSpec =
    parent.obj.attr.$[ParamSpec.Obj](s"$key-${ParamSpec.Key}").map(_.value).getOrElse(defaultSpec)

//  private final val scanValue = Vector(0.5): Vec[Double] // XXX TODO

  // updated on EDT
  private sealed trait State
  private case object EmptyState extends State
  private case class InternalState(pNode: PNode, pEdge: PEdge) extends State
  private case class SummaryState (pNode: PNode, pEdge: PEdge) extends State {
    var freeNodes  = Set.empty[PNode]
    var boundNodes = Set.empty[PNode]
  }

  private abstract class Impl[S <: SSys[S]](val parent: NuagesObj[S], val key: String, val spec: ParamSpec)
    extends NuagesParamImpl[S] with NuagesAttribute[S] {


    import TxnLike.peer

    // ---- abstract ----

    protected def input: NuagesAttribute.Input[S]

    // ---- impl ----

    override def toString = s"NuagesAttribute($parent, $key)"

    def numChannels: Int = input.numChannels

    def value: Vec[Double] = ???!

    private[this] var _state: State = EmptyState
    private[this] var _freeNodes  = Set.empty[PNode]
    private[this] var _boundNodes = Set.empty[PNode]

    private[this] def nodeSize = 0.333333f

    def addPNode(in: Input[S], n: PNode, isFree: Boolean): Unit = {
      requireEDT()
      if (isFree) {
        require (!_freeNodes.contains(n))
        _freeNodes += n
      } else {
        require (!_boundNodes.contains(n))
        _boundNodes += n
      }

      val g = main.graph

      def mkSummary() = {
        val ni  = g.addNode()
        val vii = main.visualization.getVisualItem(NuagesPanel.GROUP_GRAPH, ni)
        vii.set(NuagesPanel.COL_NUAGES, this)
        val sz  = nodeSize
        if (sz != 1.0f) vii.set(VisualItem.SIZE, sz)
        val ei  = g.addEdge(ni, parent.pNode)
        /* val ee = */ g.addEdge(n , ni)
        parent.aggr.addItem(vii)
        SummaryState(ni, ei)
      }

      _state = _state match {
        case EmptyState =>
          if (isFree) {
            val e   = g.addEdge(n, parent.pNode)
            val vi  = main.visualization.getVisualItem(NuagesPanel.GROUP_GRAPH, n)
            parent.aggr.addItem(vi)
            InternalState(n, e)
          } else {
            mkSummary()
          }

        case InternalState(ni, ei0) =>
          g.removeEdge(ei0)
          mkSummary()

        case other => other
      }
    }

    def removePNode(in: Input[S], n: PNode): Unit = {
      val isFree = _freeNodes.contains(n)
      if (isFree) {
        _freeNodes -= n
      } else {
        require (_boundNodes.contains(n))
        _boundNodes -= n
      }

      def mkEmpty() = {
        val vi = main.visualization.getVisualItem(NuagesPanel.GROUP_GRAPH, n)
        parent.aggr.removeItem(vi)
        EmptyState
      }

      _state = _state match {
        case InternalState(`n`, _) => mkEmpty()

        case prev @ SummaryState(ni, ei) if _boundNodes.isEmpty =>
          _freeNodes.size match {
            case 0 => mkEmpty()
            case 1 => // become internal
              val g   = main.graph
              val vis = main.visualization
              val vi  = vis.getVisualItem(NuagesPanel.GROUP_GRAPH, ni)
              parent.aggr.removeItem(vi)
              g.removeNode(ni)
              val n1  = _freeNodes.head
              val e   = g.addEdge(n1, parent.pNode)
              val vi1 = vis.getVisualItem(NuagesPanel.GROUP_GRAPH, n1)
              parent.aggr.addItem(vi1)
              InternalState(n, e)

            case _ => prev // no change
          }

        case other => other
      }
    }

    //    def mapping: Option[Mapping[S]] = ...

    def removeMapping()(implicit tx: S#Tx): Unit = ???!

    /** Adjusts the control with the given normalized value. */
    def setControl(v: Vec[Double], instant: Boolean): Unit = ???!

    protected def boundsResized(): Unit = ()

    protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit =
      drawName(g, vi, NuagesDataImpl.diam * vi.getSize.toFloat * 0.5f)

    def dispose()(implicit tx: S#Tx): Unit = {
      input.dispose()
      parent.params.remove(key)
    }
  }
}