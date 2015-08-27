package de.sciss.nuages
package impl

import java.awt.geom.Point2D

import de.sciss.lucre.synth.{Sys, Txn}
import de.sciss.nuages.NuagesPanel.GROUP_GRAPH
import de.sciss.nuages.impl.PanelImpl.VisualLink
import prefuse.Visualization
import prefuse.data.Graph
import prefuse.visual.{AggregateTable, VisualItem}

import scala.concurrent.stm.TxnExecutor

trait PanelImplGuiFuns[S <: Sys[S]] {
  // ---- abstract ----

  protected def graph        : Graph

  // ---- impl ----

//  def initNodeGUI(obj: VisualObj[S], vn: VisualNode[S], locO: Option[Point2D]): VisualItem = {
//    val pNode = vn.pNode
//    val _vi   = visualization.getVisualItem(GROUP_GRAPH, pNode)
//    val same  = vn == obj
//    locO.fold {
//      if (!same) {
//        val _vi1 = visualization.getVisualItem(GROUP_GRAPH, obj.pNode)
//        _vi.setEndX(_vi1.getX)
//        _vi.setEndY(_vi1.getY)
//      }
//    } { loc =>
//      _vi.setEndX(loc.getX)
//      _vi.setEndY(loc.getY)
//    }
//    _vi
//  }

//   protected def addNodeGUI(vp: VisualObj[S], links: List[VisualLink[S]], locO: Option[Point2D]): Unit = {
//    initNodeGUI(vp, vp, locO)
//
//    vp.inputs.foreach { case (_, vScan) =>
//      initNodeGUI(vp, vScan, locO)
//    }
//    vp.outputs.foreach { case (_, vScan) =>
//      initNodeGUI(vp, vScan, locO)
//    }
//
//    vp.params.foreach { case (_, vParam) =>
//      initNodeGUI(vp, vParam, locO)
//    }
//
//    links.foreach { link =>
//      link.source.outputs.get(link.sourceKey).foreach { sourceVisScan =>
//        if (link.isScan)
//          link.sink.inputs.get(link.sinkKey).foreach { sinkVisScan =>
//            addScanScanEdgeGUI(sourceVisScan, sinkVisScan)
//          }
//        else
//          link.sink.params.get(link.sinkKey).foreach { sinkVisCtl =>
//            addScanControlEdgeGUI(sourceVisScan, sinkVisCtl)
//          }
//      }
//    }
//  }

  def addScanScanEdgeGUI(source: VisualScan[S], sink: VisualScan[S]): Unit = {
    val pEdge = graph.addEdge(source.pNode, sink.pNode)
    source.sinks   += pEdge
    sink  .sources += pEdge
  }

  def addScanControlEdgeGUI(source: VisualScan[S], sink: VisualControl[S]): Unit = {
    /* val pEdge = */ graph.addEdge(source.pNode, sink.pNode)
    source.mappings += sink
    // sink  .mapping.foreach { m => ... }
  }

  def removeEdgeGUI(source: VisualScan[S], sink: VisualScan[S]): Unit =
    source.sinks.find(_.getTargetNode == sink.pNode).foreach { pEdge =>
      source.sinks   -= pEdge
      sink  .sources -= pEdge
      graph.removeEdge(pEdge)
    }
}
