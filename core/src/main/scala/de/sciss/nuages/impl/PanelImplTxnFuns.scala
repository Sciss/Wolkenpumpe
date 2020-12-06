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

import de.sciss.lucre.expr.SourcesAsRunnerMap
import de.sciss.lucre.synth.Txn
import de.sciss.lucre.{Folder, Obj, Source, SpanLikeObj}
import de.sciss.nuages.Nuages.Surface
import de.sciss.span.Span
import de.sciss.proc.{Proc, Runner, Timeline}

import scala.concurrent.stm.TxnLocal

trait PanelImplTxnFuns[T <: Txn[T]] {
  self: NuagesPanel[T] =>

  protected def nuagesH: Source[T, Nuages[T]]

  // ---- impl ----

  private[this] val locHintMap = TxnLocal(Map.empty[Obj[T], Point2D])

  final def setLocationHint(obj: Obj[T], loc: Point2D)(implicit tx: T): Unit =
    locHintMap.transform(_ + (obj -> loc))(tx.peer)

  final def removeLocationHint(obj: Obj[T])(implicit tx: T): Option[Point2D] =
    locHintMap.getAndTransform(_ - obj)(tx.peer).get(obj)

  private def addToTimeline(tl: Timeline.Modifiable[T], frameOffset: Long, obj: Obj[T])
                                 (implicit tx: T): Unit = {
    val pos     = transport.position
    val start   = pos - frameOffset
    val span    = Span.From(start): SpanLikeObj[T]
    val spanEx  = SpanLikeObj.newVar[T](span)
    tl.add(spanEx, obj)
  }

  private def connectToNewSink(out: Proc.Output[T], sink: Obj[T], key: String = Proc.mainIn)
                                    (implicit tx: T): Unit =
    nuages.surface match {
      case Surface.Timeline(_) =>
        val pos = transport.position

        val colAttr = sink.attr
        require(!colAttr.contains(key))
        val in      = Timeline[T]()
        colAttr.put(Proc.mainIn, in)
        addToTimeline(in, pos, out)

      case Surface.Folder(_) =>
        val colAttr = sink.attr
        require(!colAttr.contains(key))
        val in      = Folder[T]()
        colAttr.put(Proc.mainIn, in)
        in.addLast(out)
    }

  override final def prepareAndLocate(proc: Obj[T], pt: Point2D)(implicit tx: T): Unit = {
    prepareObj(proc)
    setLocationHint(proc, pt)
  }

  override final def addNewObject(obj: Obj[T])(implicit tx: T): Unit =
    nuages.surface match {
      case Surface.Timeline(tl) =>
        addToTimeline(tl, 0L, obj)
      case Surface.Folder(f) =>
        f.addLast(obj)
    }

  private def finalizeProcAndCollector(proc: Obj[T], colSrcOpt: Option[Obj[T]], pt: Point2D)
                                            (implicit tx: T): Unit = {
    val colOpt = colSrcOpt.map(Obj.copy(_))

    prepareAndLocate(proc, if (colOpt.isEmpty) pt else new Point2D.Double(pt.getX, pt.getY - 30))

    colOpt.foreach { col =>
      prepareAndLocate(col, new Point2D.Double(pt.getX, pt.getY + 30))
      proc match {
        case genP: Proc[T] =>
          val out = genP.outputs.add(Proc.mainOut)
          connectToNewSink(out, sink = col)
        case _ =>
      }
    }

    addNewObject(proc)
    colOpt.foreach(addNewObject)
  }

  final def insertMacro(macroF: Folder[T], pt: Point2D)(implicit tx: T): Unit = {
    val copies = Nuages.copyGraph(macroF.iterator.toIndexedSeq)
    copies.foreach { cpy =>
      finalizeProcAndCollector(cpy, None, pt)
    }
  }

  final def createGenerator(genSrc: Obj[T], colSrcOpt: Option[Obj[T]], pt: Point2D)(implicit tx: T): Obj[T] = {
    val gen = Obj.copy(genSrc)
    finalizeProcAndCollector(gen, colSrcOpt, pt)
    gen
  }

  final def insertFilter(pred: Proc.Output[T], succ: NuagesAttribute[T], fltSrc: Obj[T], fltPt: Point2D)
                        (implicit tx: T): Obj[T] = {
    val flt = Obj.copy(fltSrc)
    prepareAndLocate(flt, fltPt)

    flt match {
      case fltP: Proc[T] =>
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

  final def appendFilter(pred: Proc.Output[T], fltSrc: Obj[T], colSrcOpt: Option[Obj[T]], fltPt: Point2D)
                        (implicit tx: T): Obj[T] = {
    val flt = Obj.copy(fltSrc)

    connectToNewSink(out = pred, sink = flt)
    finalizeProcAndCollector(flt, colSrcOpt, fltPt)
    flt
  }

  private def exec(obj: Obj[T], key: String)(implicit tx: T): Unit =
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

  protected final def prepareObj(obj: Obj[T])(implicit tx: T): Unit = exec(obj, Nuages.attrPrepare)
  protected final def disposeObj(obj: Obj[T])(implicit tx: T): Unit = exec(obj, Nuages.attrDispose)
}