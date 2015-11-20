package de.sciss.nuages
package impl

import java.awt.geom.Point2D

import de.sciss.lucre.expr.{SpanObj, SpanLikeObj}
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

  protected def transport: Transport[S]

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

        (proc, colOpt) match {
          case (genP: Proc[S], Some(colP: Proc[S])) =>

            val out     = genP.outputs.add(Proc.scanMainOut)
            val colAttr = colP.attr
            val in      = colAttr.$[Timeline](Proc.scanMainIn).fold[Unit] {
              val links = Timeline[S]
              links.add(SpanLikeObj.newVar(span), out)
              colAttr.put(Proc.scanMainIn, links)
            } {
              case links: Timeline.Modifiable[S] =>
                links.add(SpanLikeObj.newVar(span), out)
              case _ => // XXX TODO --- what to here, immutable timeline?
            }

          case _ =>
        }

        def addToTimeline(obj: Obj[S])(implicit tx: S#Tx): Unit = {
          val spanEx  = SpanLikeObj.newVar[S](span)
          tl.add(spanEx, obj)
        }

        addToTimeline(proc)
        colOpt.foreach(addToTimeline)

      case Surface.Folder   (f) =>
        ???
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

  // SCAN
//  def insertFilter(pred: Scan[S], succ: Scan[S], fltSrc: Obj[S], fltPt: Point2D)(implicit tx: S#Tx): Unit = {
//    val flt = Obj.copy(fltSrc)
//
//    flt match {
//      case fltP: Proc[S] =>
//        val procFlt  = fltP
//        pred.add(procFlt.inputs.add(Proc.scanMainIn))
//        // we may handle 'sinks' here by ignoring them when they don't have an `"out"` scan.
//        for {
//          fltOut <- procFlt.outputs.get(Proc.scanMainOut)
//        } {
//          pred  .remove(succ)
//          fltOut.add   (succ)
//        }
//
//      case _ =>
//    }
//
//    finalizeProcAndCollector(flt, None, fltPt)
//  }

  def appendFilter(pred: Output[S], fltSrc: Obj[S], colSrcOpt: Option[Obj[S]], fltPt: Point2D)
                  (implicit tx: S#Tx): Unit = {
    val flt = Obj.copy(fltSrc)

    flt match {
      case fltP: Proc[S] =>
        val fltAttr = fltSrc.attr
        fltAttr.get(Proc.scanMainIn) match {
          case Some(f: Folder[S]) => f.addLast(pred)
          // case Some(tl: Timeline.Modifiable[S]) => tl.add(..., pred)
          case Some(other) =>
            val f = Folder[S]
            f.addLast(other)
            f.addLast(pred)
            fltAttr.put(Proc.scanMainIn, f)
          case None => fltAttr.put(Proc.scanMainIn, pred)
        }
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