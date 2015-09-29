package de.sciss.nuages
package impl

import java.awt.geom.Point2D

import de.sciss.lucre.expr.SpanLikeObj
import de.sciss.lucre.stm.Obj
import de.sciss.lucre.synth.Sys
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
                                      (implicit tx: S#Tx): Unit =
    for (tl <- nuages.timeline.modifiableOption) {
      val colOpt = colSrcOpt.map(in => Obj.copy(in))

      (proc, colOpt) match {
        case (genP: Proc[S], Some(colP: Proc[S])) =>
          val procGen = genP
          val procCol = colP
          val scanOut = procGen.outputs.add(Proc.scanMainOut)
          val scanIn  = procCol.inputs .add(Proc.scanMainIn )
          scanOut.add(scanIn)

        case _ =>
      }

      prepareObj(proc)
      colOpt.foreach(prepareObj)

      setLocationHint(proc, if (colOpt.isEmpty) pt else new Point2D.Double(pt.getX, pt.getY - 30))
      colOpt.foreach(setLocationHint(_, new Point2D.Double(pt.getX, pt.getY + 30)))

      addToTimeline(tl, proc)
      colOpt.foreach(addToTimeline(tl, _))
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

  private def addToTimeline(tl: Timeline.Modifiable[S], obj: Obj[S])(implicit tx: S#Tx): Unit = {
    // val imp = ExprImplicits[S]
    val time    = transport.position
    val span    = Span.From(time)
    val spanEx  = SpanLikeObj.newVar[S](span)
    tl.add(spanEx, obj)
  }

  def insertFilter(pred: Scan[S], succ: Scan[S], fltSrc: Obj[S], fltPt: Point2D)(implicit tx: S#Tx): Unit = {
    val flt = Obj.copy(fltSrc)

    flt match {
      case fltP: Proc[S] =>
        val procFlt  = fltP
        pred.add(procFlt.inputs.add(Proc.scanMainIn))
        // we may handle 'sinks' here by ignoring them when they don't have an `"out"` scan.
        for {
          fltOut <- procFlt.outputs.get(Proc.scanMainOut)
        } {
          pred  .remove(succ)
          fltOut.add   (succ)
        }

      case _ =>
    }

    finalizeProcAndCollector(flt, None, fltPt)
  }

  def appendFilter(pred: Scan[S], fltSrc: Obj[S], colSrcOpt: Option[Obj[S]], fltPt: Point2D)
                  (implicit tx: S#Tx): Unit = {
    val flt = Obj.copy(fltSrc)

    flt match {
      case fltP: Proc[S] =>
        val procFlt  = fltP
        pred.add(procFlt.inputs.add(Proc.scanMainIn))
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