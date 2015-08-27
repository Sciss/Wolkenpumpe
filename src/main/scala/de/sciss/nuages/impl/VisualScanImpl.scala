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
import de.sciss.synth.proc.Scan
import prefuse.data.Edge
import prefuse.visual.VisualItem

object VisualScanImpl {
  def apply[S <: Sys[S]](parent: VisualObj[S], scan: Scan[S], key: String, isInput: Boolean)
                        (implicit tx: S#Tx): VisualScanImpl[S] = {
    val res = new VisualScanImpl(parent, tx.newHandle(scan), key = key, isInput = isInput)
    res.init(scan)
    res
  }
}
final class VisualScanImpl[S <: Sys[S]] private(val parent: VisualObj[S],
                                                val scanH: stm.Source[S#Tx, Scan[S]],
                                                val key: String, val isInput: Boolean)
  extends VisualParamImpl[S] with VisualScan[S] {

  import VisualDataImpl._

  protected def nodeSize = 0.333333f

  var sources   = Set.empty[Edge]
  var sinks     = Set.empty[Edge]
  var mappings  = Set.empty[VisualControl[S]]

  def scan(implicit tx: S#Tx): Scan[S] = scanH()

  private[this] var observers = List.empty[Disposable[S#Tx]]

  def init(scan: Scan[S])(implicit tx: S#Tx): this.type = {
    val map = if (isInput) parent.inputs else parent.outputs
    map.put(key, this)(tx.peer)
    main.deferVisTx(initGUI())
    if (!isInput) observers ::= scan.changed.react { implicit tx => upd =>
      upd.changes.foreach {
        case Scan.Added  (sink) => withScans(sink)(main.addScanScanEdgeGUI)
        case Scan.Removed(sink) => withScans(sink)(main.removeEdgeGUI     )
      }
    }
    this
  }

  private def withScans(sink: Scan.Link[S])(fun: (VisualScan[S], VisualScan[S]) => Unit)(implicit tx: S#Tx): Unit =
    for {
      sinkInfo    <- main.scanMap  .get(sink.id)
      sinkVis     <- main.nodeMap  .get(sinkInfo.timedID)
      sinkVisScan <- sinkVis.inputs.get(sinkInfo.key)(tx.peer)
    } main.deferVisTx {
      fun(this, sinkVisScan)
    }

  def dispose()(implicit tx: S#Tx): Unit = {
    val map = if (isInput) parent.inputs else parent.outputs
    map.remove(key)(tx.peer)
    main.scanMap.remove(scan.id)
    observers.foreach(_.dispose())
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
