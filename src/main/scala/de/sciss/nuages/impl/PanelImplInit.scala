package de.sciss.nuages
package impl

import de.sciss.lucre.stm
import de.sciss.lucre.stm.Disposable
import de.sciss.lucre.swing.deferTx
import de.sciss.lucre.synth.Sys
import de.sciss.span.SpanLike
import de.sciss.synth.proc.{AuralObj, Transport, Timeline}
import prefuse.controls.Control

import scala.concurrent.stm.Ref

trait PanelImplInit[S <: Sys[S]] {
  // _: PanelImpl[S] =>

  // ---- abstract ----

  protected var transportObserver: Disposable[S#Tx]
  protected var timelineObserver : Disposable[S#Tx]

  protected def auralObserver: Ref[Option[Disposable[S#Tx]]]
  protected def auralTimeline: Ref[Option[AuralObj.Timeline[S]]]

  protected def nodeMap: stm.IdentifierMap[S#ID, S#Tx, NuagesObj[S]]

  protected def transport: Transport[S]

  protected def auralObjAdded(vp: NuagesObj[S], aural: AuralObj[S])(implicit tx: S#Tx): Unit

  protected def auralObjRemoved(aural: AuralObj[S])(implicit tx: S#Tx): Unit

  protected def disposeAuralObserver()(implicit tx: S#Tx): Unit

  protected def guiInit(): Unit

  def addNode   (span: SpanLike, timed: Timeline.Timed[S])(implicit tx: S#Tx): Unit
  def removeNode(span: SpanLike, timed: Timeline.Timed[S])(implicit tx: S#Tx): Unit

  protected def main: NuagesPanel[S]

  // ---- impl ----

  private var  _keyControl: Control with Disposable[S#Tx] = _
  protected def keyControl: Control with Disposable[S#Tx] = _keyControl

  def init(tlObj: Timeline[S])(implicit tx: S#Tx): this.type = {
    _keyControl = KeyControl(main)
    deferTx(guiInit())
    transportObserver = transport.react { implicit tx => {
      case Transport.ViewAdded(_, auralTL: AuralObj.Timeline[S]) =>
        val obs = auralTL.contents.react { implicit tx => {
          case AuralObj.Timeline.ViewAdded  (_, timed, view) =>
            nodeMap.get(timed).foreach { vp =>
              auralObjAdded(vp, view)
            }
          case AuralObj.Timeline.ViewRemoved(_, view) =>
            auralObjRemoved(view)
        }}
        disposeAuralObserver()
        auralTimeline.set(Some(auralTL))(tx.peer)
        auralObserver.set(Some(obs    ))(tx.peer)

      case Transport.ViewRemoved(_, auralTL: AuralObj.Timeline[S]) =>
        disposeAuralObserver()

      case _ =>
    }}
    transport.addObject(tlObj)

    val tl = tlObj
    timelineObserver = tl.changed.react { implicit tx => upd =>
      upd.changes.foreach {
        case Timeline.Added(span, timed) =>
          if (span.contains(transport.position)) addNode(span, timed)
        // XXX TODO - update scheduler

        case Timeline.Removed(span, timed) =>
          if (span.contains(transport.position)) removeNode(span, timed)
        // XXX TODO - update scheduler

        // ELEM
        //          case Timeline.Element(timed, Obj.UpdateT(obj, changes)) =>
        //            nodeMap.get(timed.id).foreach { visObj =>
        //              changes.foreach {
        //                case Obj.AttrRemoved(key, elem) =>
        //                  deferVisTx {
        //                    visObj.params.get(key).foreach { vc =>
        //                      removeControlGUI(visObj, vc)
        //                    }
        //                  }
        //
        //                case Obj.AttrAdded(key, elem) =>
        //                  elem match {
        //                    case dObj: DoubleObj[S] => addScalarControl(visObj, key, dObj)
        //                    case sObj: Scan     [S] => addScanControl  (visObj, key, sObj)
        //                    case _ =>
        //                  }
        //
        //                case other => if (DEBUG) println(s"OBSERVED: Timeline.Element - $other")
        //              }
        //            }

        case other => if (PanelImpl.DEBUG) println(s"OBSERVED: $other")
      }
    }

    tl.intersect(transport.position).foreach { case (span, elems) =>
      elems.foreach(addNode(span, _))
    }
    this
  }
}
