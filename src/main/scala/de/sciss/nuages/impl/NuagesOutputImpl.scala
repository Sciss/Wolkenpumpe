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
import de.sciss.synth.proc.Output
import prefuse.data.Edge
import prefuse.visual.VisualItem

object NuagesOutputImpl {
  def apply[S <: Sys[S]](parent: NuagesObj[S], output: Output[S])
                        (implicit tx: S#Tx): NuagesOutputImpl[S] = {
    val res = new NuagesOutputImpl(parent, tx.newHandle(output), key = output.key)
    res.init(output)
    res
  }

  private def addEdgeGUI[S <: Sys[S]](source: NuagesOutput[S], sink: NuagesOutput[S]): Unit = {
    val graph = source.parent.main.graph
    val isNew = !source.sinks.exists(_.getTargetNode == sink.pNode)
    if (isNew) {
      val pEdge = graph.addEdge(source.pNode, sink.pNode)
      source.sinks += pEdge
      sink.sources += pEdge
    }
  }
}
final class NuagesOutputImpl[S <: Sys[S]] private(val parent: NuagesObj[S],
                                                  val outputH: stm.Source[S#Tx, Output[S]],
                                                  val key: String)
  extends NuagesParamImpl[S] with NuagesOutput[S] {

  import NuagesDataImpl._
  import NuagesOutputImpl.addEdgeGUI

  protected def nodeSize = 0.333333f

  var sources   = Set.empty[Edge]
  var sinks     = Set.empty[Edge]
  var mappings  = Set.empty[NuagesAttribute[S]]

  def output(implicit tx: S#Tx): Output[S] = outputH()

  private[this] var observers = List.empty[Disposable[S#Tx]]

  def init(output: Output[S])(implicit tx: S#Tx): this.type = {
    val map = parent.outputs
    map.put(key, this)(tx.peer)
    main.deferVisTx(initGUI())      // IMPORTANT: first
    main.scanMapPut(output.id, this)  // IMPORTANT: second
    // SCAN
//    observers ::= output.changed.react { implicit tx => upd =>
//      upd.changes.foreach {
//        case Scan.Added  (sink) => withScan(sink)(addEdgeGUI(this, _))
//        case Scan.Removed(sink) => withScan(sink)(removeEdgeGUI)
//      }
//    }
//    output.iterator.foreach { sink =>
//      withScan(sink)(addEdgeGUI(this, _))
//    }
    this
  }

  // SCAN
//  private[this] def removeEdgeGUI(sink: NuagesParam[S]): Unit =
//    sinks.find(_.getTargetNode == sink.pNode).foreach { pEdge =>
//      this.sinks   -= pEdge
//      sink.sources -= pEdge
//      main.graph.removeEdge(pEdge)
//    }

  // SCAN
//  private[this] def withScan(target: Scan.Link[S])(fun: VisualScan[S] => Unit)
//                             (implicit tx: S#Tx): Unit =
//    for {
//      targetVis <- main.scanMapGet(target.peerID)
//    } main.deferVisTx {
//      fun(targetVis)
//    }

  def dispose()(implicit tx: S#Tx): Unit = {
    val map = parent.outputs
    map.remove(key)(tx.peer)
    main.scanMapRemove(output.id)
    observers.foreach(_.dispose())
    main.deferVisTx(disposeGUI())
  }

  private def initGUI(): Unit = {
    requireEDT()
    mkPNodeAndEdge()
  }

  override def itemPressed(vi: VisualItem, e: MouseEvent, pt: Point2D): Boolean = (key != "in") && {
    // println("itemPressed")
    if (e.getClickCount == 2) {
      parent.main.showAppendFilterDialog(this, e.getPoint)
    }
    true
  }

  protected def boundsResized() = ()

  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit =
    drawName(g, vi, diam * vi.getSize.toFloat * 0.5f)
}
