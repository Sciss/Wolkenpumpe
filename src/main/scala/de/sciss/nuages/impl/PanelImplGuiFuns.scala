package de.sciss.nuages
package impl

import de.sciss.lucre.synth.Sys
import prefuse.data.Graph

trait PanelImplGuiFuns[S <: Sys[S]] {
  // ---- abstract ----

  protected def graph        : Graph

  // ---- impl ----

  def addScanControlEdgeGUI(source: VisualScan[S], sink: VisualControl[S]): Unit = {
    /* val pEdge = */ graph.addEdge(source.pNode, sink.pNode)
    source.mappings += sink
    // sink  .mapping.foreach { m => ... }
  }
}
