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

import de.sciss.lucre.stm
import de.sciss.lucre.stm.TxnLike
import de.sciss.lucre.synth.{AudioBus, Node, Synth, Sys}
import de.sciss.synth.proc.{AuralObj, Proc}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.TMap

trait PanelImplReact[S <: Sys[S]] {
  // ---- abstract ----

  def deferVisTx(thunk: => Unit)(implicit tx: TxnLike): Unit

  protected def main: NuagesPanel[S]

  def nodeMap     : stm.IdentifierMap[S#ID, S#Tx, NuagesObj [S]]

//  protected def scanMap     : stm.IdentifierMap[S#ID, S#Tx, VisualScan[S]]
  protected def missingScans: stm.IdentifierMap[S#ID, S#Tx, List[NuagesAttribute[S]]]

//  protected def scanMapPut(id: S#ID, view: NuagesOutput[S])(implicit tx: S#Tx): Unit
//  protected def scanMapGet     (id: S#ID)(implicit tx: S#Tx): Option[NuagesOutput[S]]

//  /** Transaction local hack */
//  protected def waitForScanView(id: S#ID)(fun: NuagesOutput[S] => Unit)(implicit tx: S#Tx): Unit

//  protected def auralTimeline: Ref[Option[AuralObj.Timeline[S]]]

  protected def getAuralScanData(aural: AuralObj[S], key: String = Proc.scanMainOut)
                                (implicit tx: S#Tx): Option[(AudioBus, Node)]

  protected def auralToViewMap: TMap[AuralObj[S], NuagesObj[S]]
  protected def viewToAuralMap: TMap[NuagesObj[S], AuralObj[S]]

  protected def mkMeter  (bus: AudioBus, node: Node)(fun: Double => Unit)(implicit tx: S#Tx): Synth

  protected def mkMonitor(bus: AudioBus, node: Node)(fun: Vec[Double] => Unit)(implicit tx: S#Tx): Synth

//  protected def disposeObj(obj: Obj[S])(implicit tx: S#Tx): Unit

  // ---- impl ----

  // SCAN
//  def assignMapping(source: Scan[S], vSink: VisualControl[S])(implicit tx: S#Tx): Unit = {
//    implicit val itx = tx.peer
//
//    def withView(/* debug: Boolean, */ vScan: VisualScan[S]): Unit = {
//      val vObj = vScan.parent
//      vSink.mapping.foreach { m =>
//        // println(s"---flonky1 $debug")
//        deferVisTx {
//          // println(s"---flonky2 $debug")
//          m.source        = Some(vScan)
//          val sourceNode  = vScan.pNode
//          val sinkNode    = vSink.pNode
//          main.graph.addEdge(sourceNode, sinkNode)
//          vScan.mappings += vSink
//        }
//        // XXX TODO -- here we need something analogous to `waitForAux`
//        // XXX TODO -- total hack, defer till last moment
//        tx.beforeCommit { implicit tx =>
//          viewToAuralMap.get(vObj).foreach { aural =>
//            getAuralScanData(aural, vScan.key).foreach {
//              case (bus, node) =>
//                m.synth() = Some(mkMonitor(bus, node)(v => vSink.value = v))
//            }
//          }
//        }
//      }
//    }
//
//    scanMapGet(source.id).fold(waitForScanView(source.id)(withView))(withView)
//  }

  protected def auralObjAdded(vp: NuagesObj[S], aural: AuralObj[S])(implicit tx: S#Tx): Unit = {
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
}