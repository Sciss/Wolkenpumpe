package de.sciss.nuages
package impl

import java.awt.geom.Point2D

import de.sciss.lucre.expr.DoubleObj
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Obj, TxnLike}
import de.sciss.lucre.synth.{Synth, Node, AudioBus, Sys}
import de.sciss.nuages.impl.PanelImpl.{VisualLink, ScanInfo}
import de.sciss.span.SpanLike
import de.sciss.synth.proc.{AuralObj, Scan, Proc, Timeline}

import scala.concurrent.stm.{TMap, Ref}

trait PanelImplReact[S <: Sys[S]] {
  // ---- abstract ----

  def deferVisTx(thunk: => Unit)(implicit tx: TxnLike): Unit

  protected def main: NuagesPanel[S]

  protected def removeLocationHint(obj: Obj[S])(implicit tx: S#Tx): Option[Point2D]

  protected def nodeMap     : stm.IdentifierMap[S#ID, S#Tx, VisualObj         [S]]
  protected def scanMap     : stm.IdentifierMap[S#ID, S#Tx, ScanInfo          [S]]
  protected def missingScans: stm.IdentifierMap[S#ID, S#Tx, List[VisualControl[S]]]

  protected def auralTimeline: Ref[Option[AuralObj.Timeline[S]]]

  protected def getAuralScanData(aural: AuralObj[S], key: String = Proc.scanMainOut)
                                (implicit tx: S#Tx): Option[(AudioBus, Node)]

  protected def auralToViewMap: TMap[AuralObj[S], VisualObj[S]]
  protected def viewToAuralMap: TMap[VisualObj[S], AuralObj[S]]

  protected def mkMeter(bus: AudioBus, node: Node)(fun: Float => Unit)(implicit tx: S#Tx): Synth

  protected def disposeObj(obj: Obj[S])(implicit tx: S#Tx): Unit

  // ---- impl ----

  def addNode(span: SpanLike, timed: Timeline.Timed[S])(implicit tx: S#Tx): Unit = {
    val id    = timed.id
    val obj   = timed.value
    val config = main.config
    val locO  = removeLocationHint(obj)
    val vp    = VisualObj[S](main, locO, timed, hasMeter = config.meters, hasSolo = config.soloChannels.isDefined)

    var links: List[VisualLink[S]] = obj match {
      case objT: Proc[S] =>
        val proc  = objT
        val l1: List[VisualLink[S]] = proc.inputs.iterator.flatMap { case (key, scan) =>
          val info = new ScanInfo[S](id, key, isInput = true)
          addScan(vp, scan, info)
        } .toList

        val l2: List[VisualLink[S]] = proc.outputs.iterator.flatMap { case (key, scan) =>
          val info = new ScanInfo[S](id, key, isInput = false)
          addScan(vp, scan, info)
        } .toList

        l1 ++ l2

      case _ => Nil
    }

    // check existing mappings; establish visual links or remember missing scans
    vp.params.foreach { case (sinkKey, vSink) =>
      vSink.mapping.foreach { m =>
        assert(m.source.isEmpty)  // must be missing at this stage
        val scan  = m.scan
        val sid   = scan.id
        scanMap.get(sid).fold[Unit] {
          val list  = vSink :: missingScans.getOrElse(sid, Nil)
          missingScans.put(sid, list)
        } { info =>
          nodeMap.get(info.timedID).foreach { vObj =>
            assignMapping(source = scan, vSink = vSink)
            links ::= new VisualLink(vObj, info.key, vp /* aka vCtl.parent */, sinkKey, isScan = false)
          }
        }
      }
    } (tx.peer)

    auralTimeline.get(tx.peer).foreach { auralTL =>
      auralTL.getView(timed).foreach { auralObj =>
        auralObjAdded(vp, auralObj)
      }
    }
  }

//  def addScalarControl(visObj: VisualObj[S], key: String, dObj: DoubleObj[S])(implicit tx: S#Tx): Unit = {
//    val vc = VisualControl.scalar(visObj, key, dObj)
//    addControl(visObj, vc)
//  }
//
//  def addScanControl(visObj: VisualObj[S], key: String, sObj: Scan[S])(implicit tx: S#Tx): Unit = {
//    implicit val itx = tx.peer
//    val vc    = VisualControl.scan(visObj, key, sObj)
//    val scan  = sObj
//    assignMapping(source = scan, vSink = vc)
//    addControl(visObj, vc)
//  }

//  private def addControl(visObj: VisualObj[S], vc: VisualControl[S])(implicit tx: S#Tx): Unit = {
//    // val key     = vc.key
//    // val locOpt  = locHintMap.get(tx.peer).get(visObj -> key)
//    // println(s"locHintMap($visObj -> $key) = $locOpt")
//    deferVisTx {
//      addControlGUI(visObj, vc /* , locOpt */)
//    }
//  }

  def assignMapping(source: Scan[S], vSink: VisualControl[S])(implicit tx: S#Tx): Unit = {
    implicit val itx = tx.peer
    scanMap.get(source.id).foreach { info =>
      nodeMap.get(info.timedID).foreach { vObj =>
        vObj.outputs.get(info.key).foreach { vScan =>
          vSink.mapping.foreach { m =>
            m.source = Some(vScan)  // XXX TODO -- not cool, should be on the EDT
            viewToAuralMap.get(vObj).foreach { aural =>
              getAuralScanData(aural, info.key).foreach {
                case (bus, node) =>
                  m.synth() = Some(mkMeter(bus, node)(vSink.value = _))
              }
            }
          }
        }
      }
    }
  }

  protected def auralObjAdded(vp: VisualObj[S], aural: AuralObj[S])(implicit tx: S#Tx): Unit = {
    val config = main.config
    if (config.meters) getAuralScanData(aural).foreach { case (bus, node) =>
      val meterSynth = mkMeter(bus, node)(vp.meterUpdate)
      vp.meterSynth = Some(meterSynth)
    }
    auralToViewMap.put(aural, vp)(tx.peer)
    viewToAuralMap.put(vp, aural)(tx.peer)
  }

  protected def auralObjRemoved(aural: AuralObj[S])(implicit tx: S#Tx): Unit = {
    auralToViewMap.remove(aural)(tx.peer).foreach { vp =>
      viewToAuralMap.remove(vp)(tx.peer)
      vp.meterSynth = None
    }
  }

  def removeNode(span: SpanLike, timed: Timeline.Timed[S])(implicit tx: S#Tx): Unit = {
    val id   = timed.id
    val obj  = timed.value
    nodeMap.get(id).foreach { vp =>
      vp.dispose()
      disposeObj(obj)

      // note: we could look for `solo` and clear it
      // if relevant; but the bus-reader will automatically
      // go to dummy, so let's just save the effort.
      // orphaned solo will be cleared when calling
      // `setSolo` another time or upon frame disposal.
    }
  }

  // looks for existing sinks and sources of a scan that
  // are already represented in the GUI. for those, creates
  // visual links and returns them (they'll have to be
  // "materialized" in terms of prefuse-edges later).
  //
  // resolves entries in missingScans as well.
  private def addScan(vi: VisualObj[S], scan: Scan[S], info: ScanInfo[S])(implicit tx: S#Tx): List[VisualLink[S]] = {
    val sid = scan.id
    scanMap.put(sid, info)  // stupid look-up
    var res = List.empty[VisualLink[S]]
    scan.iterator.foreach {
      case Scan.Link.Scan(target) =>
        scanMap.get(target.id).foreach {
          case ScanInfo(targetTimedID, targetKey, _) =>
            nodeMap.get(targetTimedID).foreach { targetVis =>
              import info.isInput
              val sourceVis = if (isInput) targetVis else vi
              val sourceKey = if (isInput) targetKey else info.key
              val sinkVis   = if (isInput) vi else targetVis
              val sinkKey   = if (isInput) info.key else targetKey

              res ::= new VisualLink(sourceVis, sourceKey, sinkVis, sinkKey, isScan = true)
            }
        }
      case _ =>
    }

    missingScans.get(sid).foreach { controls =>
      missingScans.remove(sid)
      controls  .foreach { ctl =>
        assignMapping(source = scan, vSink = ctl)
        res ::= new VisualLink(vi, info.key, ctl.parent, ctl.key, isScan = false)
      }
    }

    res
  }
}