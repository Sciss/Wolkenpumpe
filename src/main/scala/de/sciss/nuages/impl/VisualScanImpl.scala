/*
 *  VisualScanImpl.scala
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

import java.awt.Graphics2D
import java.awt.event.MouseEvent
import java.awt.geom.Point2D

import de.sciss.lucre.stm
import de.sciss.lucre.stm.Disposable
import de.sciss.lucre.swing.requireEDT
import de.sciss.lucre.synth.Sys
import prefuse.data.Edge
import prefuse.visual.VisualItem

object VisualScanImpl {
  // SCAN
//  def apply[S <: Sys[S]](parent: VisualObj[S], scan: Scan[S], key: String, isInput: Boolean)
//                        (implicit tx: S#Tx): VisualScanImpl[S] = {
//    val res = new VisualScanImpl(parent, tx.newHandle(scan), key = key, isInput = isInput)
//    res.init(scan)
//    res
//  }

  private def addEdgeGUI[S <: Sys[S]](source: VisualScan[S], sink: VisualScan[S]): Unit = {
    val graph = source.parent.main.graph
    val isNew = !source.sinks.exists(_.getTargetNode == sink.pNode)
    if (isNew) {
      val pEdge = graph.addEdge(source.pNode, sink.pNode)
      source.sinks += pEdge
      sink.sources += pEdge
    }
  }
}
// SCAN
//final class VisualScanImpl[S <: Sys[S]] private(val parent: VisualObj[S],
//                                                val scanH: stm.Source[S#Tx, Scan[S]],
//                                                val key: String, val isInput: Boolean)
//  extends VisualParamImpl[S] with VisualScan[S] {
//
//  import VisualDataImpl._
//  import VisualScanImpl.addEdgeGUI
//
//  protected def nodeSize = 0.333333f
//
//  var sources   = Set.empty[Edge]
//  var sinks     = Set.empty[Edge]
//  var mappings  = Set.empty[VisualControl[S]]
//
//  def scan(implicit tx: S#Tx): Scan[S] = scanH()
//
//  private[this] var observers = List.empty[Disposable[S#Tx]]
//
//  def init(scan: Scan[S])(implicit tx: S#Tx): this.type = {
//    val map = if (isInput) parent.inputs else parent.outputs
//    map.put(key, this)(tx.peer)
//    main.deferVisTx(initGUI())      // IMPORTANT: first
//    main.scanMapPut(scan.id, this)  // IMPORTANT: second
//    if (!isInput) {
//      observers ::= scan.changed.react { implicit tx => upd =>
//        upd.changes.foreach {
//          case Scan.Added  (sink) => withScan(sink)(addEdgeGUI(this, _))
//          case Scan.Removed(sink) => withScan(sink)(removeEdgeGUI)
//        }
//      }
//      scan.iterator.foreach { sink =>
//        withScan(sink)(addEdgeGUI(this, _))
//      }
//    } else {
//      scan.iterator.foreach { source =>
//        withScan(source)(addEdgeGUI(_, this))
//      }
//    }
//    this
//  }
//
//  private[this] def removeEdgeGUI(sink: VisualScan[S]): Unit =
//    sinks.find(_.getTargetNode == sink.pNode).foreach { pEdge =>
//      this.sinks   -= pEdge
//      sink.sources -= pEdge
//      main.graph.removeEdge(pEdge)
//    }
//
//  private[this] def withScan(target: Scan.Link[S])(fun: VisualScan[S] => Unit)
//                             (implicit tx: S#Tx): Unit =
//    for {
//      targetVis <- main.scanMapGet(target.peerID)
//    } main.deferVisTx {
//      fun(targetVis)
//    }
//
//  def dispose()(implicit tx: S#Tx): Unit = {
//    val map = if (isInput) parent.inputs else parent.outputs
//    map.remove(key)(tx.peer)
//    main.scanMapRemove(scan.id)
//    observers.foreach(_.dispose())
//    main.deferVisTx(disposeGUI())
//  }
//
//  private def initGUI(): Unit = {
//    requireEDT()
//    mkPNodeAndEdge()
//  }
//
//  override def itemPressed(vi: VisualItem, e: MouseEvent, pt: Point2D): Boolean = (key != "in") && {
//    // println("itemPressed")
//    if (e.getClickCount == 2) {
//      parent.main.showAppendFilterDialog(this, e.getPoint)
//    }
//    true
//  }
//
//  protected def boundsResized() = ()
//
//  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit =
//    drawName(g, vi, diam * vi.getSize.toFloat * 0.5f)
//}
