/*
 *  PanelImplTxnFuns.scala
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

import java.awt.geom.Point2D

import de.sciss.lucre.expr.SpanLikeObj
import de.sciss.lucre.stm.Obj
import de.sciss.lucre.synth.Sys
import de.sciss.nuages.Nuages.Surface
import de.sciss.span.Span
import de.sciss.synth.proc._

import scala.concurrent.stm.TxnLocal

trait PanelImplTxnFuns[S <: Sys[S]] {
  // ---- abstract ----

  protected def nuages(implicit tx: S#Tx): Nuages[S]

  protected def workspace: WorkspaceHandle[S]

  def transport: Transport[S]

  def isTimeline: Boolean

  // ---- impl ----

  def setLocationHint(obj: Obj[S], loc: Point2D)(implicit tx: S#Tx): Unit =
    locHintMap.transform(_ + (obj -> loc))(tx.peer)

  def removeLocationHint(obj: Obj[S])(implicit tx: S#Tx): Option[Point2D] =
    locHintMap.getAndTransform(_ - obj)(tx.peer).get(obj)

  private val locHintMap = TxnLocal(Map.empty[Obj[S], Point2D])

  private def finalizeProcAndCollector(proc: Obj[S], colSrcOpt: Option[Obj[S]], pt: Point2D)
                                      (implicit tx: S#Tx): Unit = {
    val colOpt = colSrcOpt.map(in => Obj.copy(in))

    prepareObj(proc)
    colOpt.foreach(prepareObj)

    setLocationHint(proc, if (colOpt.isEmpty) pt else new Point2D.Double(pt.getX, pt.getY - 30))
    colOpt.foreach(setLocationHint(_, new Point2D.Double(pt.getX, pt.getY + 30)))

    nuages.surface match {
      case Surface.Timeline(tl) =>
        val time    = transport.position
        val span    = Span.From(time): SpanLikeObj[S]

        def addToTimeline(tl0: Timeline.Modifiable[S], obj: Obj[S])(implicit tx: S#Tx): Unit = {
          val spanEx = SpanLikeObj.newVar[S](span)
          tl0.add(spanEx, obj)
        }

        (proc, colOpt) match {
          case (genP: Proc[S], Some(colP: Proc[S])) =>

            val out     = genP.outputs.add(Proc.scanMainOut)
            val colAttr = colP.attr
            val in      = colAttr.get(Proc.scanMainIn).fold[Timeline.Modifiable[S]] {
              val _links = Timeline[S]
              colAttr.put(Proc.scanMainIn, _links)
              _links
            } {
              case _links: Timeline.Modifiable[S] => _links
              case prev => // what to here, immutable timeline?
                val _links = Timeline[S]
                addToTimeline(_links, prev) // XXX TODO -- or Span.all?
                colAttr.put(Proc.scanMainIn, _links)
                _links
            }
            addToTimeline(in, out)

          case _ =>
        }

        addToTimeline(tl, proc)
        colOpt.foreach(addToTimeline(tl, _))

      case Surface.Folder(f) =>
        (proc, colOpt) match {
          case (genP: Proc[S], Some(colP: Proc[S])) =>
            val out     = genP.outputs.add(Proc.scanMainOut)
            val colAttr = colP.attr
            val in      = colAttr.get(Proc.scanMainIn).fold[Folder[S]] {
              val _links = Folder[S]
              colAttr.put(Proc.scanMainIn, _links)
              _links
            } {
              case _links: Folder[S] => _links
              case prev => // what to here, immutable timeline?
                val _links = Folder[S]
                _links.addLast(prev)
                colAttr.put(Proc.scanMainIn, _links)
                _links
            }
            in.addLast(out)

          case _ =>
        }

        f.addLast(proc)
        colOpt.foreach(f.addLast)
    }
  }

  def insertMacro(macroF: Folder[S], pt: Point2D)(implicit tx: S#Tx): Unit = {
    val copies = Nuages.copyGraph(macroF.iterator.toIndexedSeq)
    copies.foreach { cpy =>
      finalizeProcAndCollector(cpy, None, pt)
    }
  }

  def createGenerator(genSrc: Obj[S], colSrcOpt: Option[Obj[S]], pt: Point2D)(implicit tx: S#Tx): Unit = {
    val gen = Obj.copy(genSrc)
    finalizeProcAndCollector(gen, colSrcOpt, pt)
  }

  // Removes a child from the `.attr` of a parent. If the value currently stored with
  // the attribute map is a collection, tries to smartly remove the child from that collection.
  // Returns `true` if the child was found and removed.
  final def removeCollectionAttribute(parent: Obj[S], key: String, child: Obj[S])
                                     (implicit tx: S#Tx): Boolean = {
    val attr = parent.attr

    def mkTimeline(): (Timeline.Modifiable[S], SpanLikeObj.Var[S]) = {
      val tl    = Timeline[S]
      val frame = parentTimeOffset()
      val span  = SpanLikeObj.newVar[S](Span.until(frame))
      (tl, span)
    }

    attr.get(key).exists {
      case `child` =>
        if (isTimeline) { // convert scalar to timeline
          val (tl, span) = mkTimeline()
          tl.add(span, child)
          attr.put(key, tl)
        } else {
          attr.remove(key)
        }
        true

      case f: Folder[S] =>
        val idx = f.indexOf(child)
        idx >= 0 && {
          if (isTimeline) { // convert folder to timeline
            val (tl, span) = mkTimeline()
            f.iterator.zipWithIndex.foreach { case (elem, idx1) =>
              if (idx1 != idx) {
                val span2 = SpanLikeObj.newVar(span())
                tl.add(span2, elem)
              }
            }
            attr.put(key, tl)
          } else {
            f.removeAt(idx)
            if (f.isEmpty) attr.remove(key)
          }
          true
        }

      case tl: Timeline.Modifiable[S] =>
        val frame   = parentTimeOffset()
        val itEntry = tl.intersect(frame).flatMap { case (_, seq) =>
          seq.find(_.value == child)
        }
        itEntry.hasNext && {
          val predEntry   = itEntry.next()
          val newSpanVal  = predEntry.span.value.intersect(Span.until(frame))
          if (isTimeline && newSpanVal.nonEmpty) {
            predEntry.span match {
              case SpanLikeObj.Var(vr) => vr() = newSpanVal
              case _ =>
                val newSpan = SpanLikeObj.newVar[S](newSpanVal)
                tl.remove(predEntry.span, predEntry.value)
                tl.add   (newSpan       , predEntry.value)
            }
          } else {
            tl.remove(predEntry.span, predEntry.value)
            if (tl.isEmpty) attr.remove(key)
          }
          true
        }

      case _ => false
    }
  }

  final def addCollectionAttribute(parent: Obj[S], key: String, child: Obj[S])
                                  (implicit tx: S#Tx): Unit = {
    val attr = parent.attr

    def mkSpan(): SpanLikeObj.Var[S] = {
      val frame = parentTimeOffset()
      SpanLikeObj.newVar[S](Span.from(frame))
    }

    def mkTimeline(): (Timeline.Modifiable[S], SpanLikeObj.Var[S]) = {
      val tl    = Timeline[S]
      val span  = mkSpan()
      (tl, span)
    }

    attr.get(key).fold[Unit] {
      if (isTimeline) {
        val (tl, span) = mkTimeline()
        tl.add(span, child)
        attr.put(key, tl)
      } else {
        attr.put(key, child)
      }

    } {
      case f: Folder[S] =>
        if (isTimeline) {
          val (tl, span) = mkTimeline()
          f.iterator.foreach { elem =>
            val span2 = SpanLikeObj.newVar(span())
            tl.add(span2, elem)
          }
          tl.add(span, child)
          attr.put(key, tl)

        } else {
          f.addLast(child)
        }

      case tl: Timeline.Modifiable[S] if isTimeline =>
        val span = mkSpan()
        tl.add(span, child)

      case other =>
        if (isTimeline) {
          val (tl, span) = mkTimeline()
          val span2 = SpanLikeObj.newVar(span())  // we want the two spans to be independent
          tl.add(span2, other)
          tl.add(span , child)
          attr.put(key, tl)

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
          attr.put(key, f)
        }
    }
  }

  // N.B.: Currently AuralTimelineAttribute does not pay
  // attention to the parent object's time offset. Therefore,
  // to match with the current audio implementation, we also
  // do not take that into consideration, but might so in the future...
  private[this] def parentTimeOffset()(implicit tx: S#Tx): Long = transport.position

  def insertFilter(pred: Output[S], succ: (Obj[S], String), fltSrc: Obj[S], fltPt: Point2D)
                  (implicit tx: S#Tx): Unit = {
    val flt = Obj.copy(fltSrc)

    flt match {
      case fltP: Proc[S] =>
        val (succObj, succKey) = succ
        removeCollectionAttribute(parent = succObj, key = succKey, child = pred)

        connect(pred, fltP)
        // we may handle 'sinks' here by ignoring them when they don't have an `"out"` scan.
        for {
          fltOut <- fltP.outputs.get(Proc.scanMainOut)
        } {
          // pred  .remove(succ)
          connect(fltOut, succObj, succKey) // fltOut.add   (succ)
        }

      case _ =>
    }

    finalizeProcAndCollector(flt, None, fltPt)
  }

  private[this] def connect(pred: Output[S], succ: Obj[S], succKey: String = Proc.scanMainIn)
                           (implicit tx: S#Tx): Unit =
    addCollectionAttribute(parent = succ, key = succKey, child = pred)

  def appendFilter(pred: Output[S], fltSrc: Obj[S], colSrcOpt: Option[Obj[S]], fltPt: Point2D)
                  (implicit tx: S#Tx): Unit = {
    val flt = Obj.copy(fltSrc)

    flt match {
      case fltP: Proc[S] => connect(pred, fltP)
      case _ =>
    }

    finalizeProcAndCollector(flt, colSrcOpt, fltPt)
  }

  private def exec(obj: Obj[S], key: String)(implicit tx: S#Tx): Unit =
    for (self <- obj.attr.$[Action](key)) {
      implicit val cursor = transport.scheduler.cursor
      self.execute(Action.Universe(self, workspace, invoker = Some(obj)))
    }

  protected def prepareObj(obj: Obj[S])(implicit tx: S#Tx): Unit = exec(obj, "nuages-prepare")
  protected def disposeObj(obj: Obj[S])(implicit tx: S#Tx): Unit = exec(obj, "nuages-dispose")
}