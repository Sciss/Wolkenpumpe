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

  // def display      : Display
  protected def visualization: Visualization
  protected def graph        : Graph
  // def visualGraph  : VisualGraph
  protected def aggrTable    : AggregateTable

  // ---- impl ----

  protected def removeNodeGUI(vp: VisualObj[S]): Unit = {
    ???
//    aggrTable.removeTuple(vp.aggr)
//    graph.removeNode(vp.pNode)
//    vp.inputs.foreach { case (_, vs) =>
//      graph.removeNode(vs.pNode)
//    }
//    vp.outputs.foreach { case (_, vs) =>
//      graph.removeNode(vs.pNode)
//    }
//    vp.params.foreach { case (_, vc) =>
//      graph.removeNode(vc.pNode)
//    }
  }

  def initNodeGUI(obj: VisualObj[S], vn: VisualNode[S], locO: Option[Point2D]): VisualItem = {
    val pNode = vn.pNode
    val _vi   = visualization.getVisualItem(GROUP_GRAPH, pNode)
    val same  = vn == obj
    locO.fold {
      if (!same) {
        val _vi1 = visualization.getVisualItem(GROUP_GRAPH, obj.pNode)
        _vi.setEndX(_vi1.getX)
        _vi.setEndY(_vi1.getY)
      }
    } { loc =>
      _vi.setEndX(loc.getX)
      _vi.setEndY(loc.getY)
    }
    _vi
  }

  protected def addNodeGUI(vp: VisualObj[S], links: List[VisualLink[S]], locO: Option[Point2D]): Unit = {
    ???
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
  }

//  protected def addControlGUI(vp: VisualObj[S], vc: VisualControl[S] /* , locO: Option[Point2D] */): Unit = {
//    initNodeGUI(vp, vc, None /* locO */)
//    val old = vp.params.get(vc.key)
//    vp.params += vc.key -> vc
//    vc.mapping.foreach { m =>
//      m.source.foreach { vSrc =>
//        /* val pEdge = */ graph.addEdge(vSrc.pNode, vc.pNode)
//        vSrc.mappings += vc
//        // m.pEdge = Some(pEdge)
//      }
//    }
//    old.foreach(removeControlGUI(vp, _))
//  }

  def removeControlGUI(visObj: VisualObj[S], vc: VisualControl[S]): Unit = {
    ???
//    val key = vc.key
//    visObj.params -= key
//    val _vi = visualization.getVisualItem(GROUP_GRAPH, vc.pNode)
//    visObj.aggr.removeItem(_vi)
//    // val loc = new Point2D.Double(_vi.getX, _vi.getY)
//    TxnExecutor.defaultAtomic { implicit itx =>
//      // println(s"setLocationHint($visObj -> $key, $loc)")
//      // setLocationHint(visObj -> key, loc)
//      vc.mapping.foreach { m =>
//        m.synth.swap(None).foreach { synth =>
//          implicit val tx = Txn.wrap(itx)
//          synth.dispose()
//        }
//      }
//    }
//    graph.removeNode(vc.pNode)
  }

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
