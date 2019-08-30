/*
 *  NuagesAttributeImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2019 Hanns Holger Rutz. All rights reserved.
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

import de.sciss.lucre.expr.{BooleanObj, DoubleObj, DoubleVector, IntObj, LongObj, SpanLikeObj}
import de.sciss.lucre.stm.{Disposable, Folder, Obj, Sys, TxnLike}
import de.sciss.lucre.swing.LucreSwing.requireEDT
import de.sciss.lucre.synth.{Sys => SSys}
import de.sciss.nuages.NuagesAttribute.{Factory, Input, Parent}
import de.sciss.nuages.NuagesPanel.GROUP_GRAPH
import de.sciss.span.Span
import de.sciss.synth.proc.AuralObj.Proc
import de.sciss.synth.proc.{AuralAttribute, AuralObj, EnvSegment, Grapheme, Output, Runner, TimeRef, Timeline}
import prefuse.data.{Edge => PEdge, Node => PNode}
import prefuse.visual.VisualItem

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref
import scala.swing.Color

object NuagesAttributeImpl {
  private[this] final val sync = new AnyRef
  
  def addFactory(f: Factory): Unit = sync.synchronized {
    val tid = f.typeId
    if (map.contains(tid)) throw new IllegalArgumentException(s"View factory for type $tid already installed")
    map += tid -> f
  }

  def factories: Iterable[Factory] = map.values

  def apply[S <: SSys[S]](key: String, _value: Obj[S], parent: NuagesObj[S])
                         (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute[S] = {
    val spec          = getSpec(parent, key)
//    val spec          = getSpec(_value)
    val _frameOffset  = parent.frameOffset
    val res = new Impl[S](parent = parent, key = key, spec = spec) { self =>
      protected val inputView: Input[S] =
        mkInput(attr = self, parent = self, frameOffset = _frameOffset, value = _value)
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

  def getFactory[S <: Sys[S]](value: Obj[S]): Option[Factory] = {
    val tid = value.tpe.typeId
    val opt = map.get(tid)
    opt
  }

  private[this] var map = Map[Int, Factory](
    IntObj      .typeId -> NuagesIntAttrInput,
    DoubleObj   .typeId -> NuagesDoubleAttrInput,
    BooleanObj  .typeId -> NuagesBooleanAttrInput,
//  FadeSpec.Obj.typeId -> FadeSpecAttribute,
    DoubleVector.typeId -> NuagesDoubleVectorAttrInput,
    Grapheme    .typeId -> NuagesGraphemeAttrInput,
    Output      .typeId -> NuagesOutputAttrInput,
    Folder      .typeId -> NuagesFolderAttrInput,
    Timeline    .typeId -> NuagesTimelineAttrInput,
    EnvSegment  .typeId -> NuagesEnvSegmentAttrInput
  )
  
  // ----
  
  private val defaultSpec = ParamSpec()

  def getSpec[S <: Sys[S]](parent: NuagesObj[S], key: String)(implicit tx: S#Tx): ParamSpec =
    parent.obj.attr.$[ParamSpec.Obj](ParamSpec.composeKey(key)).map(_.value).getOrElse(defaultSpec)

//  def getSpec[S <: Sys[S]](value: Obj[S])(implicit tx: S#Tx): ParamSpec =
//    value.attr.$[ParamSpec.Obj](ParamSpec.Key).map(_.value).getOrElse(defaultSpec)

  // updated on EDT
  private sealed trait State {
    def isSummary: Boolean
  }
  private case object EmptyState extends State { def isSummary = false }
  private case class InternalState(pNode: PNode) extends State { def isSummary = false }
  private case class SummaryState (pNode: PNode, pEdge: PEdge) extends State {
    var freeNodes   = Set.empty[PNode]
    var boundNodes  = Set.empty[PNode]
    def isSummary   = true
  }

  private abstract class Impl[S <: SSys[S]](val parent: NuagesObj[S], val key: String, val spec: ParamSpec)
    extends RenderAttrDoubleVec[S] with NuagesParamImpl[S] with NuagesAttribute[S] { self =>

    import TxnLike.peer

    // ---- abstract ----

    protected def inputView: NuagesAttribute.Input[S]

    // ---- impl ----

    // ... state ...

    // edt
    private[this] var _state        = EmptyState: State
    private[this] var _freeNodes    = Map.empty[PNode, PEdge]
    private[this] var _boundNodes   = Map.empty[PNode, PEdge]

    // txn
    private[this] val auralObjObs   = Ref(Disposable.empty[S#Tx])
    private[this] val auralAttrObs  = Ref(Disposable.empty[S#Tx])
    private[this] val auralTgtObs   = Ref(Disposable.empty[S#Tx])
    private[this] val valueSynthRef = Ref(Disposable.empty[S#Tx])

    @volatile
    protected var valueA: A = _

    // ... methods ...

//    final val isControl: Boolean = key != "in"  // XXX TODO --- not cool
    final val isControl: Boolean = /* Wolkenpumpe.ALWAYS_CONTROL || */  key != "in"  // XXX TODO --- not cool

    // loop

    final def attribute: NuagesAttribute[S] = this

    final def inputParent                (implicit tx: S#Tx): Parent[S] = this
    final def inputParent_=(p: Parent[S])(implicit tx: S#Tx): Unit      = throw new UnsupportedOperationException

    // proxy

    final def numChildren(implicit tx: S#Tx): Int = inputView.numChildren

    final def tryConsume(newOffset: Long, to: Obj[S])(implicit tx: S#Tx): Boolean =
      inputView.tryConsume(newOffset = newOffset, newValue = to)

    final def collect[B](pf: PartialFunction[Input[S], B])(implicit tx: S#Tx): Iterator[B] = inputView.collect(pf)

    def input(implicit tx: S#Tx): Obj[S] = inputView.input

    // other

    override def toString = s"NuagesAttribute($parent, $key)"

    private def nodeSize = if (isControl) 1f else 0.333333f

    private def currentOffset()(implicit tx: S#Tx): Long = {
      val fr = parent.frameOffset
      if (fr == Long.MaxValue) throw new UnsupportedOperationException(s"$this.currentOffset()")
      main.transport.position - fr
    }

    private def initReplace(state: State, freeNodes: Map[PNode, PEdge], boundNodes: Map[PNode, PEdge]): Unit = {
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
              protected val inputView: Input[S] = newInput
            }
            main.deferVisTx {
              res.initReplace(self._state, freeNodes = self._freeNodes, boundNodes = self._boundNodes)
            }
            res
          }
      }
    }

    final def updateChild(before: Obj[S], now: Obj[S], dt: Long, clearRight: Boolean)(implicit tx: S#Tx): Unit =
      inputView match {
        case inP: Parent[S] =>
          inP.updateChild(before = before, now = now, dt = dt, clearRight = clearRight)

        case _ =>
          updateChildHere(before = before, now = now, dt = dt)
      }

    private def updateChildHere(before: Obj[S], now: Obj[S], dt: Long)(implicit tx: S#Tx): Unit = {
      val objAttr = parent.obj.attr
      val value   = if (main.isTimeline) {
        val gr      = Grapheme[S]
        val start   = currentOffset() + dt

        log(s"$this updateChild($before, $now - $start / ${TimeRef.framesToSecs(start)})")

        if (start != 0L) {
          val timeBefore = LongObj.newVar[S](0L) // XXX TODO ?
          gr.add(timeBefore, before)
        }
        val timeNow = LongObj.newVar[S](start)
        ParamSpec.copyAttr(source = before, target = gr)
        gr.add(timeNow, now)
        gr
      } else {
        now
      }
      val found = objAttr.get(key)
      if (!found.contains(before))   // Option.contains not available in Scala 2.10
        sys.error(s"updateChild($before, $now) -- found $found")

      objAttr.put(key, value)
    }

    def addChild(child: Obj[S])(implicit tx: S#Tx): Unit =
      inputView match {
        case inP: Parent[S] => inP.addChild(child)
        case _ =>
          val objAttr = parent.obj.attr

          def mkSpan(): SpanLikeObj.Var[S] = {
            val start = currentOffset()
            SpanLikeObj.newVar[S](Span.from(start))
          }

          def mkTimeline(): (Timeline.Modifiable[S], SpanLikeObj.Var[S]) = {
            val tl    = Timeline[S]
            val span  = mkSpan()
            (tl, span)
          }

          objAttr.get(key).fold[Unit] {
            // XXX TODO -- this shouldn't happen, because otherwise there would be no NuagesAttribute (ourself)
            if (main.isTimeline) {
              val (tl, span) = mkTimeline()
              // XXX TODO --- copy attr from child?
              tl.add(span, child)
              objAttr.put(key, tl)
            } else {
              objAttr.put(key, child)
            }

          } {
//            case f: Folder[S] =>
//              if (main.isTimeline) {
//                val (tl, span) = mkTimeline()
//                f.iterator.foreach { elem =>
//                  val span2 = SpanLikeObj.newVar(span())
//                  tl.add(span2, elem)
//                }
//                tl.add(span, child)
//                objAttr.put(key, tl)
//
//              } else {
//                f.addLast(child)
//              }
//
//            case tl: Timeline.Modifiable[S] if main.isTimeline =>
//              val span = mkSpan()
//              tl.add(span, child)
//
//            case
            other =>
              if (main.isTimeline) {
                val (tl, span) = mkTimeline()
                val span2 = SpanLikeObj.newVar(span())  // we want the two spans to be independent
                tl.add(span2, other)
                tl.add(span , child)
                ParamSpec.copyAttr(source = other, target = tl)
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
                ParamSpec.copyAttr(source = other, target = f)
                objAttr.put(key, f)
              }
          }
      }

    def removeChild(child: Obj[S])(implicit tx: S#Tx): Unit =
      inputView match {
        case inP: Parent[S] =>
          inP.removeChild(child)
        case _ =>
          val objAttr = parent.obj.attr
          if (main.isTimeline) {
            val tl          = Timeline[S]
            val span        = SpanLikeObj.newVar[S](Span.until(currentOffset()))
            ParamSpec.copyAttr(source = child, target = tl)
            tl.add(span, child)
            objAttr.put(key, tl)
          } else {
            objAttr.remove(key)
          }
      }

    final def addPNode(n: PNode, isFree: Boolean): Unit = {
      requireEDT()
      val g = main.graph

      def mkSummary() = {
        val ns  = g.addNode()
        val vis = main.visualization
        val vi  = vis.getVisualItem(GROUP_GRAPH, ns)
        vi.set(NuagesPanel.COL_NUAGES, this)
        val sz  = nodeSize
        if (sz != 1.0f) vi.set(VisualItem.SIZE, sz)
        val ei  = g.addEdge(ns, parent.pNode)
        val ee  = g.addEdge(n , ns)
        val pVi = vis.getVisualItem(GROUP_GRAPH, parent.pNode)
        vi.setEndX(pVi.getEndX)
        vi.setEndY(pVi.getEndY)
        addAggr(ns)
        SummaryState(ns, ei) -> ee
      }

      val oldState = _state
      val (newState, newEdge) = oldState match {
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
        val vis = main.visualization
        val pVi = vis.getVisualItem(GROUP_GRAPH, parent.pNode)
        val vi  = vis.getVisualItem(GROUP_GRAPH, n)
        vi.setEndX(pVi.getEndX)
        vi.setEndY(pVi.getEndY)
      } else {
        require (!_boundNodes.contains(n))
        _boundNodes += n -> newEdge
      }

      if (newState != oldState && newState.isSummary && isControl) {
        valueA = Vector(0.0) //  Vector.fill(math.max(1, numChannels))(0.0)
        // XXX TODO --- launch rockets
      }
    }

    private def removeAggr(n: PNode): Unit = {
      val vi = main.visualization.getVisualItem(GROUP_GRAPH, n)
      logAggr(s"rem $vi@${vi.hashCode.toHexString} - $this")
      // VALIDATE_AGGR("before removeAggr")
      assert(parent.aggregate.containsItem(vi))
      parent.aggregate.removeItem(vi)
      // VALIDATE_AGGR("after  removeAggr")
    }

    private def addAggr(n: PNode): Unit = {
      val vi = main.visualization.getVisualItem(GROUP_GRAPH, n)
      logAggr(s"add $vi${vi.hashCode.toHexString} - $this")
      // VALIDATE_AGGR("before empty>free")
      assert(!parent.aggregate.containsItem(vi))
      parent.aggregate.addItem(vi)
    }

    final def removePNode(n: PNode): Unit = {
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
            val vi = main.visualization.getVisualItem(GROUP_GRAPH, ns)
            g.removeNode(ns)
            assert(!vi.isValid)
            assert(!attribute.parent.aggregate.containsItem(vi))

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
    
    @inline
    private def showsValue: Boolean = _state.isSummary && isControl

    protected def boundsResized(): Unit = if (showsValue) updateContainerArea()

    import NuagesDataImpl.{colrMapped, diam}

    protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit =
      if (showsValue) {
        renderValueDetail(g, vi)
        drawLabel(g, vi, diam * vi.getSize.toFloat * 0.33333f, name)
      } else {
        drawName(g, vi, NuagesDataImpl.diam * vi.getSize.toFloat * 0.5f)
      }

    protected def renderDrag(g: Graphics2D): Unit = ()

    protected def valueColor: Color = colrMapped

    def dispose()(implicit tx: S#Tx): Unit = {
      auralObjRemoved()
      inputView.dispose()
    }

    private def setAuralScalarValue(v: Vec[Double])(implicit tx: S#Tx): Unit = {
      disposeValueSynth()
//      println(s"setAuralScalarValue $key")
      valueA = v
    }

    private def setAuralValue(v: AuralAttribute.Value)(implicit tx: S#Tx): Unit = {
      v match {
        case AuralAttribute.ScalarValue (f )  => setAuralScalarValue(Vector(f.toDouble))
        case AuralAttribute.ScalarVector(xs)  => setAuralScalarValue(xs.map(_.toDouble))
        case AuralAttribute.Stream(nodeRef, bus) =>
//          println(s"setAuralStream $key")
          val syn = main.mkValueMeter(bus, nodeRef.node) { xs =>
            valueA = xs // setAuralScalarValue(xs)
          }
          valueSynthRef.swap(syn).dispose()
      }
    }

    private def checkAuralTarget(aa: AuralAttribute[S])(implicit tx: S#Tx): Unit =
      aa.targetOption.foreach { tgt =>
        tgt.valueOption.foreach(setAuralValue)
        val obs = tgt.react { implicit tx => setAuralValue }
        auralTgtObs.swap(obs).dispose()
      }

    private def disposeValueSynth()(implicit tx: S#Tx): Unit =
      valueSynthRef.swap(Disposable.empty).dispose()

    private def auralTgtRemoved()(implicit tx: S#Tx): Unit = {
      disposeValueSynth()
      auralTgtObs.swap(Disposable.empty).dispose()
    }

    private def auralAttrAdded(aa: AuralAttribute[S])(implicit tx: S#Tx): Unit = {
      checkAuralTarget(aa)
      val obs = aa.react { implicit tx => {
        case Runner.Running => checkAuralTarget(aa)
        case Runner.Stopped => auralTgtRemoved()
        case _ =>
      }}
      auralAttrObs.swap(obs).dispose()
    }

    private def auralAttrRemoved()(implicit tx: S#Tx): Unit = {
      auralTgtRemoved()
      auralAttrObs.swap(Disposable.empty).dispose()
    }

    def auralObjAdded(aural: Proc[S])(implicit tx: S#Tx): Unit = if (isControl) {
      aural.getAttr(key).foreach(auralAttrAdded)
      val obs = aural.ports.react { implicit tx => {
        case AuralObj.Proc.AttrAdded  (_, aa) if aa.key == key => auralAttrAdded(aa)
        case AuralObj.Proc.AttrRemoved(_, aa) if aa.key == key => auralAttrRemoved()
        case _ =>
      }}
      auralObjObs.swap(obs).dispose()
    }

    private def auralObjRemoved()(implicit tx: S#Tx): Unit = if (isControl) {
      auralAttrRemoved()
      auralObjObs.swap(Disposable.empty).dispose()
    }

    def auralObjRemoved(aural: Proc[S])(implicit tx: S#Tx): Unit = auralObjRemoved()
  }
}