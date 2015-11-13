/*
 *  PanelImplReact.scala
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

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Obj, TxnLike}
import de.sciss.lucre.synth.{AudioBus, Node, Synth, Sys}
import de.sciss.span.SpanLike
import de.sciss.synth.proc.{AuralObj, Proc, Timeline}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.{Ref, TMap}

trait PanelImplReact[S <: Sys[S]] {
  // ---- abstract ----

  def deferVisTx(thunk: => Unit)(implicit tx: TxnLike): Unit

  protected def main: NuagesPanel[S]

  protected def removeLocationHint(obj: Obj[S])(implicit tx: S#Tx): Option[Point2D]

  protected def nodeMap     : stm.IdentifierMap[S#ID, S#Tx, VisualObj [S]]
//  protected def scanMap     : stm.IdentifierMap[S#ID, S#Tx, VisualScan[S]]
  protected def missingScans: stm.IdentifierMap[S#ID, S#Tx, List[VisualControl[S]]]

  protected def scanMapPut     (id: S#ID, view: VisualScan[S])(implicit tx: S#Tx): Unit
  protected def scanMapGet     (id: S#ID)(implicit tx: S#Tx): Option[VisualScan[S]]

  /** Transaction local hack */
  protected def waitForScanView(id: S#ID)(fun: VisualScan[S] => Unit)(implicit tx: S#Tx): Unit

  protected def auralTimeline: Ref[Option[AuralObj.Timeline[S]]]

  protected def getAuralScanData(aural: AuralObj[S], key: String = Proc.scanMainOut)
                                (implicit tx: S#Tx): Option[(AudioBus, Node)]

  protected def auralToViewMap: TMap[AuralObj[S], VisualObj[S]]
  protected def viewToAuralMap: TMap[VisualObj[S], AuralObj[S]]

  protected def mkMeter  (bus: AudioBus, node: Node)(fun: Double => Unit)(implicit tx: S#Tx): Synth

  protected def mkMonitor(bus: AudioBus, node: Node)(fun: Vec[Double] => Unit)(implicit tx: S#Tx): Synth

  protected def disposeObj(obj: Obj[S])(implicit tx: S#Tx): Unit

  // ---- impl ----

  def addNode(span: SpanLike, timed: Timeline.Timed[S])(implicit tx: S#Tx): Unit = {
    val obj   = timed.value
    val config = main.config
    val locO  = removeLocationHint(obj)
    val vp    = VisualObj[S](main, locO, timed, hasMeter = config.meters, hasSolo = config.soloChannels.isDefined)

    auralTimeline.get(tx.peer).foreach { auralTL =>
      auralTL.getView(timed).foreach { auralObj =>
        auralObjAdded(vp, auralObj)
      }
    }
  }

  def assignMapping(source: Scan[S], vSink: VisualControl[S])(implicit tx: S#Tx): Unit = {
    implicit val itx = tx.peer

    def withView(/* debug: Boolean, */ vScan: VisualScan[S]): Unit = {
      val vObj = vScan.parent
      vSink.mapping.foreach { m =>
        // println(s"---flonky1 $debug")
        deferVisTx {
          // println(s"---flonky2 $debug")
          m.source        = Some(vScan)
          val sourceNode  = vScan.pNode
          val sinkNode    = vSink.pNode
          main.graph.addEdge(sourceNode, sinkNode)
          vScan.mappings += vSink
        }
        // XXX TODO -- here we need something analogous to `waitForAux`
        // XXX TODO -- total hack, defer till last moment
        tx.beforeCommit { implicit tx =>
          viewToAuralMap.get(vObj).foreach { aural =>
            getAuralScanData(aural, vScan.key).foreach {
              case (bus, node) =>
                m.synth() = Some(mkMonitor(bus, node)(v => vSink.value = v))
            }
          }
        }
      }
    }

    scanMapGet(source.id).fold(waitForScanView(source.id)(withView))(withView)
  }

  protected def auralObjAdded(vp: VisualObj[S], aural: AuralObj[S])(implicit tx: S#Tx): Unit = {
    val config = main.config
    if (config.meters) {
      val key = if (vp.outputs.contains(Proc.scanMainOut)(tx.peer)) Proc.scanMainOut else Proc.scanMainIn
      getAuralScanData(aural, key = key).foreach { case (bus, node) =>
        val meterSynth = mkMeter(bus, node)(vp.meterUpdate)
        vp.meterSynth = Some(meterSynth)
      }
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
}