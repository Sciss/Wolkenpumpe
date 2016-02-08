/*
 *  PanelImplReact.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2016 Hanns Holger Rutz. All rights reserved.
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
import scala.concurrent.stm.{TSet, TMap}

trait PanelImplReact[S <: Sys[S]] {
  import TxnLike.peer

  // ---- abstract ----

  def deferVisTx(thunk: => Unit)(implicit tx: TxnLike): Unit

  protected def main: NuagesPanel[S]

  protected def nodeMap: stm.IdentifierMap[S#ID, S#Tx, NuagesObj [S]]

  // protected def missingScans: stm.IdentifierMap[S#ID, S#Tx, List[NuagesAttribute[S]]]

  //  protected def getAuralScanData(aural: AuralObj[S], key: String = Proc.mainOut)
  //                                (implicit tx: S#Tx): Option[(AudioBus, Node)]
  //
  //  protected def auralToViewMap: TMap[AuralObj[S], NuagesObj[S]]
  //  protected def viewToAuralMap: TMap[NuagesObj[S], AuralObj[S]]

  // protected def mkMeter  (bus: AudioBus, node: Node)(fun: Double => Unit)(implicit tx: S#Tx): Synth

  protected def mkMonitor(bus: AudioBus, node: Node)(fun: Vec[Double] => Unit)(implicit tx: S#Tx): Synth

  // ---- impl ----

  private[this] val nodeSet = TSet.empty[NuagesObj[S]]

  // SCAN
//  def assignMapping(source: Scan[S], vSink: VisualControl[S])(implicit tx: S#Tx): Unit = {
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

  /** Disposes all registered nodes. Disposes `nodeMap`. */
  protected final def disposeNodes()(implicit tx: S#Tx): Unit = {
    nodeSet.foreach(_.dispose())
    nodeSet.clear()
    nodeMap.dispose()
  }

  final def registerNode(id: S#ID, view: NuagesObj[S])(implicit tx: S#Tx): Unit = {
    val ok = nodeSet.add(view)
    if (!ok) throw new IllegalArgumentException(s"View $view was already registered")
    nodeMap.put(id, view)
  }

  final def unregisterNode(id: S#ID, view: NuagesObj[S])(implicit tx: S#Tx): Unit = {
    val ok = nodeSet.remove(view)
    if (!ok) throw new IllegalArgumentException(s"View $view was not registered")
    nodeMap.remove(id)
  }

//  protected final def auralObjAdded(vp: NuagesObj[S], aural: AuralObj[S])(implicit tx: S#Tx): Unit = {
//    val config = main.config
//    if (config.meters) {
//      val key = if (vp.hasOutput(Proc.mainOut)) Proc.mainOut else Proc.mainIn
//      getAuralScanData(aural, key = key).foreach { case (bus, node) =>
//        val meterSynth = mkMeter(bus, node)(vp.meterUpdate)
//        vp.meterSynth = Some(meterSynth)
//      }
//    }
//    auralToViewMap.put(aural, vp)
//    viewToAuralMap.put(vp, aural)
//  }

//  protected final def auralObjRemoved(aural: AuralObj[S])(implicit tx: S#Tx): Unit = {
//    auralToViewMap.remove(aural).foreach { vp =>
//      viewToAuralMap.remove(vp)
//      vp.meterSynth = None
//    }
//  }
}