/*
 *  PanelImplTxnFuns.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2017 Hanns Holger Rutz. All rights reserved.
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
import de.sciss.lucre.stm.{Cursor, Obj}
import de.sciss.lucre.synth.Sys
import de.sciss.nuages.Nuages.Surface
import de.sciss.span.Span
import de.sciss.synth.proc.{Action, Folder, Output, Proc, Timeline, Transport, WorkspaceHandle}

import scala.concurrent.stm.TxnLocal

trait PanelImplTxnFuns[S <: Sys[S]] {
  // ---- abstract ----

  protected def nuages(implicit tx: S#Tx): Nuages[S]

  protected def workspace: WorkspaceHandle[S]

  def transport: Transport[S]

  def isTimeline: Boolean

  // ---- impl ----

  private[this] val locHintMap = TxnLocal(Map.empty[Obj[S], Point2D])

  final def setLocationHint(obj: Obj[S], loc: Point2D)(implicit tx: S#Tx): Unit =
    locHintMap.transform(_ + (obj -> loc))(tx.peer)

  final def removeLocationHint(obj: Obj[S])(implicit tx: S#Tx): Option[Point2D] =
    locHintMap.getAndTransform(_ - obj)(tx.peer).get(obj)

  private[this] def addToTimeline(tl: Timeline.Modifiable[S], frameOffset: Long, obj: Obj[S])
                                 (implicit tx: S#Tx): Unit = {
    val pos     = transport.position
    val start   = pos - frameOffset
    val span    = Span.From(start): SpanLikeObj[S]
    val spanEx  = SpanLikeObj.newVar[S](span)
    tl.add(spanEx, obj)
  }

  private[this] def connectToNewSink(out: Output[S], sink: Obj[S], key: String = Proc.mainIn)
                                    (implicit tx: S#Tx): Unit =
    nuages.surface match {
      case Surface.Timeline(_) =>
        val pos = transport.position

        val colAttr = sink.attr
        require(!colAttr.contains(key))
        val in      = Timeline[S]
        colAttr.put(Proc.mainIn, in)
        addToTimeline(in, pos, out)

      case Surface.Folder(_) =>
        val colAttr = sink.attr
        require(!colAttr.contains(key))
        val in      = Folder[S]
        colAttr.put(Proc.mainIn, in)
        in.addLast(out)
    }

  private[this] def prepareAndLocate(proc: Obj[S], pt: Point2D)(implicit tx: S#Tx): Unit = {
    prepareObj(proc)
    setLocationHint(proc, pt)
  }

  private[this] def addNewObject(obj: Obj[S])(implicit tx: S#Tx): Unit =
    nuages.surface match {
      case Surface.Timeline(tl) =>
        addToTimeline(tl, 0L, obj)
      case Surface.Folder(f) =>
        f.addLast(obj)
    }

  private[this] def finalizeProcAndCollector(proc: Obj[S], colSrcOpt: Option[Obj[S]], pt: Point2D)
                                            (implicit tx: S#Tx): Unit = {
    val colOpt = colSrcOpt.map(Obj.copy(_))

    prepareAndLocate(proc, if (colOpt.isEmpty) pt else new Point2D.Double(pt.getX, pt.getY - 30))

    colOpt.foreach { col =>
      prepareAndLocate(col, new Point2D.Double(pt.getX, pt.getY + 30))
      proc match {
        case genP: Proc[S] =>
          val out = genP.outputs.add(Proc.mainOut)
          connectToNewSink(out, sink = col)
        case _ =>
      }
    }

    addNewObject(proc)
    colOpt.foreach(addNewObject)
  }

  final def insertMacro(macroF: Folder[S], pt: Point2D)(implicit tx: S#Tx): Unit = {
    val copies = Nuages.copyGraph(macroF.iterator.toIndexedSeq)
    copies.foreach { cpy =>
      finalizeProcAndCollector(cpy, None, pt)
    }
  }

  final def createGenerator(genSrc: Obj[S], colSrcOpt: Option[Obj[S]], pt: Point2D)(implicit tx: S#Tx): Unit = {
    val gen = Obj.copy(genSrc)
    finalizeProcAndCollector(gen, colSrcOpt, pt)
  }

  final def insertFilter(pred: Output[S], succ: NuagesAttribute[S], fltSrc: Obj[S], fltPt: Point2D)
                        (implicit tx: S#Tx): Unit = {
    val flt = Obj.copy(fltSrc)
    prepareAndLocate(flt, fltPt)

    flt match {
      case fltP: Proc[S] =>
        connectToNewSink(out = pred, sink = flt)
        for {
          fltOut <- fltP.outputs.get(Proc.mainOut)
        } {
          if (succ.isControl) {
            succ.updateChild(pred, fltOut)
          } else {
            succ.removeChild(pred)
            succ.addChild(fltOut)
          }
        }

      case _ =>
    }

    addNewObject(flt)
  }

  final def appendFilter(pred: Output[S], fltSrc: Obj[S], colSrcOpt: Option[Obj[S]], fltPt: Point2D)
                        (implicit tx: S#Tx): Unit = {
    val flt = Obj.copy(fltSrc)

    connectToNewSink(out = pred, sink = flt)
    finalizeProcAndCollector(flt, colSrcOpt, fltPt)
  }

  private[this] def exec(obj: Obj[S], key: String)(implicit tx: S#Tx): Unit =
    for (self <- obj.attr.$[Action](key)) {
      implicit val cursor: Cursor[S] = transport.scheduler.cursor
      val n = nuages
      NuagesImpl.use(n) {
        self.execute(Action.Universe(self, workspace, invoker = Some(obj)))
      }
    }

  protected final def prepareObj(obj: Obj[S])(implicit tx: S#Tx): Unit = exec(obj, Nuages.attrPrepare)
  protected final def disposeObj(obj: Obj[S])(implicit tx: S#Tx): Unit = exec(obj, Nuages.attrDispose)
}