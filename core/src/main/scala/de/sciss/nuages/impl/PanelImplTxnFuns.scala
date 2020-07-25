/*
 *  PanelImplTxnFuns.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2020 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import java.awt.geom.Point2D

import de.sciss.lucre.expr.{SourcesAsRunnerMap, SpanLikeObj}
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Folder, Obj}
import de.sciss.lucre.synth.Sys
import de.sciss.nuages.Nuages.Surface
import de.sciss.span.Span
import de.sciss.synth.proc.{Output, Proc, Runner, Timeline}

import scala.concurrent.stm.TxnLocal

trait PanelImplTxnFuns[S <: Sys[S]] {
  _: NuagesPanel[S] =>

  protected def nuagesH: stm.Source[S#Tx, Nuages[S]]

  // ---- impl ----

  private[this] val locHintMap = TxnLocal(Map.empty[Obj[S], Point2D])

  final def setLocationHint(obj: Obj[S], loc: Point2D)(implicit tx: S#Tx): Unit =
    locHintMap.transform(_ + (obj -> loc))(tx.peer)

  final def removeLocationHint(obj: Obj[S])(implicit tx: S#Tx): Option[Point2D] =
    locHintMap.getAndTransform(_ - obj)(tx.peer).get(obj)

  private def addToTimeline(tl: Timeline.Modifiable[S], frameOffset: Long, obj: Obj[S])
                                 (implicit tx: S#Tx): Unit = {
    val pos     = transport.position
    val start   = pos - frameOffset
    val span    = Span.From(start): SpanLikeObj[S]
    val spanEx  = SpanLikeObj.newVar[S](span)
    tl.add(spanEx, obj)
  }

  private def connectToNewSink(out: Output[S], sink: Obj[S], key: String = Proc.mainIn)
                                    (implicit tx: S#Tx): Unit =
    nuages.surface match {
      case Surface.Timeline(_) =>
        val pos = transport.position

        val colAttr = sink.attr
        require(!colAttr.contains(key))
        val in      = Timeline[S]()
        colAttr.put(Proc.mainIn, in)
        addToTimeline(in, pos, out)

      case Surface.Folder(_) =>
        val colAttr = sink.attr
        require(!colAttr.contains(key))
        val in      = Folder[S]()
        colAttr.put(Proc.mainIn, in)
        in.addLast(out)
    }

  private def prepareAndLocate(proc: Obj[S], pt: Point2D)(implicit tx: S#Tx): Unit = {
    prepareObj(proc)
    setLocationHint(proc, pt)
  }

  private def addNewObject(obj: Obj[S])(implicit tx: S#Tx): Unit =
    nuages.surface match {
      case Surface.Timeline(tl) =>
        addToTimeline(tl, 0L, obj)
      case Surface.Folder(f) =>
        f.addLast(obj)
    }

  private def finalizeProcAndCollector(proc: Obj[S], colSrcOpt: Option[Obj[S]], pt: Point2D)
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

  final def createGenerator(genSrc: Obj[S], colSrcOpt: Option[Obj[S]], pt: Point2D)(implicit tx: S#Tx): Obj[S] = {
    val gen = Obj.copy(genSrc)
    finalizeProcAndCollector(gen, colSrcOpt, pt)
    gen
  }

  final def insertFilter(pred: Output[S], succ: NuagesAttribute[S], fltSrc: Obj[S], fltPt: Point2D)
                        (implicit tx: S#Tx): Obj[S] = {
    val flt = Obj.copy(fltSrc)
    prepareAndLocate(flt, fltPt)

    flt match {
      case fltP: Proc[S] =>
        connectToNewSink(out = pred, sink = flt)
        for {
          fltOut <- fltP.outputs.get(Proc.mainOut)
        } {
          if (succ.isControl) {
            succ.updateChild(pred, fltOut, dt = 0L, clearRight = true)
          } else {
            succ.removeChild(pred)
            succ.addChild(fltOut)
          }
        }

      case _ =>
    }

    addNewObject(flt)
    flt
  }

  final def appendFilter(pred: Output[S], fltSrc: Obj[S], colSrcOpt: Option[Obj[S]], fltPt: Point2D)
                        (implicit tx: S#Tx): Obj[S] = {
    val flt = Obj.copy(fltSrc)

    connectToNewSink(out = pred, sink = flt)
    finalizeProcAndCollector(flt, colSrcOpt, fltPt)
    flt
  }

  private def exec(obj: Obj[S], key: String)(implicit tx: S#Tx): Unit =
    for (self <- obj.attr.get(key)) {
//      val n = nuages
      val r = Runner(self)
      val attr = new SourcesAsRunnerMap(Map(
        "value"   -> Left(tx.newHandle(obj)),
        "invoker" -> Left(nuagesH)
      ))
      r.prepare(attr)
      r.runAndDispose()
//      NuagesImpl.use(n) {
//        self.execute(ActionRaw.Universe(self, invoker = Some(obj)))
//      }
    }

  protected final def prepareObj(obj: Obj[S])(implicit tx: S#Tx): Unit = exec(obj, Nuages.attrPrepare)
  protected final def disposeObj(obj: Obj[S])(implicit tx: S#Tx): Unit = exec(obj, Nuages.attrDispose)
}