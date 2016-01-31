/*
 *  NuagesAttributeImpl.scala
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

import java.awt.Graphics2D

import de.sciss.lucre.expr.{SpanLikeObj, LongObj, BooleanObj, DoubleObj, DoubleVector, IntObj}
import de.sciss.lucre.stm.{Obj, Sys}
import de.sciss.lucre.swing.requireEDT
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.{Parent, Factory, Input}
import de.sciss.span.Span
import de.sciss.synth.proc.{Grapheme, Timeline, Output, Folder}
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
    val spec          = getSpec(parent, key)
    val _frameOffset  = parent.frameOffset
    val res = new Impl[S](parent = parent, key = key, spec = spec) { self =>
      protected val inputView = mkInput(attr = self, parent = self, frameOffset = _frameOffset, value = _value)
    }
    res
  }

  def mkInput[S <: SSys[S]](attr: NuagesAttribute[S], parent: Parent[S], frameOffset: Long, value: Obj[S])
                           (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] = {
    val opt = getFactory(value)
    opt.fold[Input[S]](new DummyAttrInput(attr, tx.newHandle(value))) { factory =>
      factory[S](parent = parent, frameOffset = frameOffset, value = value.asInstanceOf[factory.Repr[S]], attr = attr)
    }
  }

  private[this] def getFactory[S <: Sys[S]](value: Obj[S]): Option[Factory] = {
    val tid = value.tpe.typeID
    val opt = map.get(tid)
    opt
  }

  //  private[this] def withFactory[S <: Sys[S], A](value: Obj[S])(fun: FactoryR[_] => A): Option[A] = {
  //    val tid = value.tpe.typeID
  //    val opt = map.get(tid)
  //    opt.map(f => fun(f.asInstanceOf[FactoryR[_]]))
  //  }

  private[this] var map = Map[Int, Factory](
    IntObj      .typeID -> NuagesIntAttrInput,
    DoubleObj   .typeID -> NuagesDoubleAttrInput,
    BooleanObj  .typeID -> NuagesBooleanAttrInput,
//  FadeSpec.Obj.typeID -> FadeSpecAttribute,
    DoubleVector.typeID -> NuagesDoubleVectorAttrInput,
    Grapheme    .typeID -> NuagesGraphemeAttrInput,
    Output      .typeID -> NuagesOutputAttrInput,
    Folder      .typeID -> NuagesFolderAttrInput,
    Timeline    .typeID -> NuagesTimelineAttrInput
  )
  
  // ----
  
  private val defaultSpec = ParamSpec()

  def getSpec[S <: Sys[S]](parent: NuagesObj[S], key: String)(implicit tx: S#Tx): ParamSpec =
    parent.obj.attr.$[ParamSpec.Obj](s"$key-${ParamSpec.Key}").map(_.value).getOrElse(defaultSpec)

//  private final val scanValue = Vector(0.5): Vec[Double] // XXX TODO

  // updated on EDT
  private sealed trait State
  private case object EmptyState extends State
  private case class InternalState(pNode: PNode) extends State
  private case class SummaryState (pNode: PNode, pEdge: PEdge) extends State {
    var freeNodes  = Set.empty[PNode]
    var boundNodes = Set.empty[PNode]
  }

  private abstract class Impl[S <: SSys[S]](val parent: NuagesObj[S], val key: String, val spec: ParamSpec)
    extends NuagesParamImpl[S] with NuagesAttribute[S] with Parent[S] { self =>

    // ---- abstract ----

    protected def inputView: NuagesAttribute.Input[S]

    // ---- impl ----

    // ... state ...

    private[this] var _state      = EmptyState: State
    private[this] var _freeNodes  = Map.empty[PNode, PEdge]
    private[this] var _boundNodes = Map.empty[PNode, PEdge]

    // ... methods ...

    // loop

    final def attribute: NuagesAttribute[S] = this

    final def inputParent                (implicit tx: S#Tx): Parent[S] = this
    final def inputParent_=(p: Parent[S])(implicit tx: S#Tx): Unit      = throw new UnsupportedOperationException

    // proxy

    final def numChannels: Int = inputView.numChannels

    final def tryConsume(newOffset: Long, to: Obj[S])(implicit tx: S#Tx): Boolean =
      inputView.tryConsume(newOffset = newOffset, newValue = to)

    final def value: Vec[Double] = inputView.value

    final def collect[A](pf: PartialFunction[Input[S], A])(implicit tx: S#Tx): Iterator[A] = inputView.collect(pf)

    def input(implicit tx: S#Tx): Obj[S] = inputView.input

    // other

    override def toString = s"NuagesAttribute($parent, $key)"

    private[this] def nodeSize = 0.333333f

    private[this] def currentFrame()(implicit tx: S#Tx): Long =
      main.transport.position

    private def initReplace(state: State, freeNodes: Map[PNode, PEdge], boundNodes: Map[PNode, PEdge])
                           (implicit tx: S#Tx): Unit = {
      requireEDT()
      this._state       = state
      this._freeNodes   = freeNodes
      this._boundNodes  = boundNodes
    }

    final def tryReplace(newValue: Obj[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): Option[NuagesAttribute[S]] = {
      val opt = getFactory(newValue)
      opt.flatMap { factory =>
        factory.tryConsume(oldInput = inputView, newOffset = parent.frameOffset,
                           newValue = newValue.asInstanceOf[factory.Repr[S]])
          .map { newInput =>
            val res = new Impl[S](parent = parent, key = key, spec = spec) {
              protected val inputView = newInput
            }
            main.deferVisTx {
              res.initReplace(self._state, freeNodes = self._freeNodes, boundNodes = self._boundNodes)
            }
            res
          }
      }
    }

    final def updateChild(before: Obj[S], now: Obj[S])(implicit tx: S#Tx): Unit = {
      val objAttr = parent.obj.attr
      val value = if (main.isTimeline) {
        val gr          = Grapheme[S]
        val timeBefore  = LongObj.newVar[S](0L) // XXX TODO ?
        val timeNow     = LongObj.newVar[S](currentFrame())
        gr.add(timeBefore, before)
        gr.add(timeNow   , now)
        gr
      } else {
        now
      }
      require(objAttr.get(key).contains(before))
      objAttr.put(key, value)
    }

    def addChild(child: Obj[S])(implicit tx: S#Tx): Unit = {
      val objAttr = parent.obj.attr

      def mkSpan(): SpanLikeObj.Var[S] = {
        val frame = currentFrame()
        SpanLikeObj.newVar[S](Span.from(frame))
      }

      def mkTimeline(): (Timeline.Modifiable[S], SpanLikeObj.Var[S]) = {
        val tl    = Timeline[S]
        val span  = mkSpan()
        (tl, span)
      }

      objAttr.get(key).fold[Unit] {
        if (main.isTimeline) {
          val (tl, span) = mkTimeline()
          tl.add(span, child)
          objAttr.put(key, tl)
        } else {
          objAttr.put(key, child)
        }

      } {
        case f: Folder[S] =>
          if (main.isTimeline) {
            val (tl, span) = mkTimeline()
            f.iterator.foreach { elem =>
              val span2 = SpanLikeObj.newVar(span())
              tl.add(span2, elem)
            }
            tl.add(span, child)
            objAttr.put(key, tl)

          } else {
            f.addLast(child)
          }

        case tl: Timeline.Modifiable[S] if main.isTimeline =>
          val span = mkSpan()
          tl.add(span, child)

        case other =>
          if (main.isTimeline) {
            val (tl, span) = mkTimeline()
            val span2 = SpanLikeObj.newVar(span())  // we want the two spans to be independent
            tl.add(span2, other)
            tl.add(span , child)
            objAttr.put(key, tl)

          } else {
            // what are we going to do here...?
            // we'll pack the other into a new folder,
            // although we currently have no symmetric action
            // if other is a timeline!
            // (finding a timeline in a folder when we dissolve
            // a folder, so we might end up with nested timeline objects)

            val f = Folder[S]
            f.addLast(other)
            f.addLast(child)
            objAttr.put(key, f)
          }
      }
    }

    def removeChild(child: Obj[S])(implicit tx: S#Tx): Unit = {
      val objAttr = parent.obj.attr
      if (main.isTimeline) {
        val tl          = Timeline[S]
        val span        = SpanLikeObj.newVar[S](Span.until(currentFrame()))
        tl.add(span, child)
        objAttr.put(key, tl)
      } else {
        objAttr.remove(key)
      }
    }

    final def addPNode(in: Input[S], n: PNode, isFree: Boolean): Unit = {
      requireEDT()
      val g = main.graph

      def mkSummary() = {
        val ns  = g.addNode()
        val vis = main.visualization.getVisualItem(NuagesPanel.GROUP_GRAPH, ns)
        vis.set(NuagesPanel.COL_NUAGES, this)
        val sz  = nodeSize
        if (sz != 1.0f) vis.set(VisualItem.SIZE, sz)
        val ei  = g.addEdge(ns, parent.pNode)
        val ee  = g.addEdge(n , ns)
        addAggr(ns)
        SummaryState(ns, ei) -> ee
      }

      val (newState, newEdge) = _state match {
        case EmptyState =>
          if (isFree) {
            val e = g.addEdge(n, parent.pNode)
            addAggr(n)
            InternalState(n) -> e
          } else {
            mkSummary()
          }

        case InternalState(ni) =>
          val ei0 = _freeNodes(ni)
          g.removeEdge(ei0)   // dissolve edge of former internal node
          removeAggr(ni)      // remove former internal node from aggregate
          val res = mkSummary()
          val ei1 = g.addEdge(ni , res._1.pNode)
          _freeNodes += ni -> ei1 // register new edge of former internal node
          res

        case oldState @ SummaryState(ns, _) =>
          val ee  = g.addEdge(n, ns)
          oldState -> ee
      }

      _state = newState
      if (isFree) {
        require (!_freeNodes.contains(n))
        _freeNodes  += n -> newEdge
      } else {
        require (!_boundNodes.contains(n))
        _boundNodes += n -> newEdge
      }
    }

    private[this] def removeAggr(n: PNode): Unit = {
      val vi = main.visualization.getVisualItem(NuagesPanel.GROUP_GRAPH, n)
      logAggr(s"rem $vi@${vi.hashCode.toHexString} - $this")
      // VALIDATE_AGGR("before removeAggr")
      assert(parent.aggr.containsItem(vi))
      parent.aggr.removeItem(vi)
      // VALIDATE_AGGR("after  removeAggr")
    }

    private[this] def addAggr(n: PNode): Unit = {
      val vi = main.visualization.getVisualItem(NuagesPanel.GROUP_GRAPH, n)
      logAggr(s"add $vi${vi.hashCode.toHexString} - $this")
      // VALIDATE_AGGR("before empty>free")
      assert(!parent.aggr.containsItem(vi))
      parent.aggr.addItem(vi)
    }

    final def removePNode(in: Input[S], n: PNode): Unit = {
      requireEDT()
      val g = main.graph

      val isFree = _freeNodes.contains(n)
      val oldEdge = if (isFree) {
        val res = _freeNodes(n)
        _freeNodes -= n
        res
      } else {
        val res = _boundNodes(n)
        _boundNodes -= n
        res
      }
      g.removeEdge(oldEdge)

      _state = _state match {
        case InternalState(`n`) =>
          removeAggr(n)
          EmptyState

        case prev @ SummaryState(ns, es) if _boundNodes.isEmpty =>
          val numFree = _freeNodes.size
          if (numFree > 1) prev else {
            g.removeEdge(es)
            removeAggr(ns)
            val vi = main.visualization.getVisualItem(NuagesPanel.GROUP_GRAPH, ns)
            g.removeNode(ns)
            assert(!vi.isValid)
            assert(!attribute.parent.aggr.containsItem(vi))

            if (numFree == 0) EmptyState else { // become internal
              val (n1, _ /* e1 */)  = _freeNodes.head // the former edge is already removed because we removed `ns`
              val e2        = g.addEdge(n1, parent.pNode)
              _freeNodes   += n1 -> e2  // update with new edge
              addAggr(n1)
              InternalState(n1)
            }
          }

        case other => other
      }
    }

    protected def boundsResized(): Unit = ()

    protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit =
      drawName(g, vi, NuagesDataImpl.diam * vi.getSize.toFloat * 0.5f)

    def dispose()(implicit tx: S#Tx): Unit = {
      inputView.dispose()
      // parent.params.remove(key)
    }

//    private[this] def VALIDATE_AGGR(name: String): Unit = {
//      require (AGGR_LOCK)
//      val m_vis = main.visualization
//      val aggr  = m_vis.getGroup(PanelImpl.AGGR_PROC) // .asInstanceOf[ AggregateTable ]
//      if (aggr.getTupleCount == 0) return // do we have any to process?
//
////      println(s"VALIDATE_AGGR begin $name - $aggr@${aggr.hashCode.toHexString}")
//      var maxSz = 0
//      val iter1 = aggr.tuples()
//      while (iter1.hasNext) {
//        val item = iter1.next().asInstanceOf[AggregateItem]
////        println(s"...$item@${item.hashCode.toHexString}")
//        maxSz = math.max(maxSz, 4 * 2 * item.getAggregateSize)
//      }
////      println(s"maxSz = $maxSz")
////      println(s"VALIDATE_AGGR end   $name")
//    }
  }
}