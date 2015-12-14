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

  def insertFilter(pred: Output[S], succ: (Obj[S], String), fltSrc: Obj[S], fltPt: Point2D)
                  (implicit tx: S#Tx): Unit = {
    val flt = Obj.copy(fltSrc)

    flt match {
      case fltP: Proc[S] =>
        val (succObj, succKey) = succ
        val succAttr = succObj.attr
        succAttr.get(Proc.scanMainIn).foreach {
          case `pred` => succAttr.remove(succKey)
          case f: Folder[S]     => f.remove(pred)
          case tl: Timeline[S]  => ???
        }

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
                           (implicit tx: S#Tx): Unit = {
    val succAttr = succ.attr
    succAttr.get(succKey) match {
      case Some(f: Folder[S]) => f.addLast(pred)
      case Some(tl: Timeline.Modifiable[S]) => ??? // tl.add(..., pred)
      case Some(other) =>
        val f = Folder[S]
        f.addLast(other)
        f.addLast(pred)
        succAttr.put(succKey, f)
      case None => succAttr.put(succKey, pred)
    }
  }

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