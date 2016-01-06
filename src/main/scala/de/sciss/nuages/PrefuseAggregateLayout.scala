/*
 *  PrefuseAggregateLayout.scala
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

/**
 *  The code on which this class is based, is released under
 *  a BSD style license:
 *
 *  Copyright (c) 2004-2007 Regents of the University of California.
 *  All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions
 *  are met:
 *
 *  1. Redistributions of source code must retain the above copyright
 *  notice, this list of conditions and the following disclaimer.
 *
 *  2. Redistributions in binary form must reproduce the above copyright
 *  notice, this list of conditions and the following disclaimer in the
 *  documentation and/or other materials provided with the distribution.
 *
 *  3.  Neither the name of the University nor the names of its contributors
 *  may be used to endorse or promote products derived from this software
 *  without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE REGENTS AND CONTRIBUTORS "AS IS" AND
 *  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 *  IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 *  ARE DISCLAIMED.  IN NO EVENT SHALL THE REGENTS OR CONTRIBUTORS BE LIABLE
 *  FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 *  DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 *  OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 *  HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 *  LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 *  OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 *  SUCH DAMAGE.
 */

package de.sciss.nuages

import java.util.ConcurrentModificationException

import prefuse.action.layout.Layout
import prefuse.visual.{AggregateItem, VisualItem}
import prefuse.util.GraphicsLib

/** Scala version of the AggregateLayout by Jeffrey Heer
  * (Prefuse Demos).
  */
object PrefuseAggregateLayout {
  private val hullMargin = 5

  private def addPoint(pts: Array[Double], idx: Int, item: VisualItem, growth: Int): Unit = {
    val b = item.getBounds
    val minX = b.getMinX - growth
    val minY = b.getMinY - growth
    val maxX = b.getMaxX + growth
    val maxY = b.getMaxY + growth
    pts(idx) = minX
    pts(idx + 1) = minY
    pts(idx + 2) = minX
    pts(idx + 3) = maxY
    pts(idx + 4) = maxX
    pts(idx + 5) = minY
    pts(idx + 6) = maxX
    pts(idx + 7) = maxY
  }
}

class PrefuseAggregateLayout(aggrGroup: String) extends Layout(aggrGroup) {

  import PrefuseAggregateLayout._

  private var points = new Array[Double](8 * 4) // buffer for computing convex hulls

  def run(frac: Double): Unit = /* getVisualization.synchronized */ {
    // require(AGGR_LOCK)

    val aggr = m_vis.getGroup(aggrGroup) // .asInstanceOf[ AggregateTable ]
    if (aggr.getTupleCount == 0) return // do we have any to process?

    // update buffers
    var maxSz = 0
    val iter1 = aggr.tuples()
    while (iter1.hasNext) {
      val item = iter1.next().asInstanceOf[AggregateItem]
      // val before = Thread.getAllStackTraces
      try {
        maxSz = math.max(maxSz, item.getAggregateSize)
      } catch {
        case e: ConcurrentModificationException =>
          val aggrS = s"$aggr@${aggr.hashCode.toHexString}"
          val itemS = s"$item@${item.hashCode.toHexString}"
          Console.err.println(s"PrefuseAggregateLayout.run - ${e.getMessage} - $aggrS - $itemS")
          e.printStackTrace()
//          val allStackTraces = before // Thread.getAllStackTraces
//          import scala.collection.JavaConverters._
//          allStackTraces.asScala.foreach { case (thread, stack) =>
//            println(s"----- THREAD: $thread -----")
//            stack.take(5).foreach { elem =>
//              println(elem)
//            }
//          }
//          println("-----")

//          val again = item.getAggregateSize
//          println(again)
      }
    }
    maxSz *= 8
    if (maxSz > points.length) {
      points = new Array[Double](maxSz + 8)
    }

    // compute and assign convex hull for each aggregate
    val iter2 = m_vis.visibleItems(aggrGroup)
    while (iter2.hasNext) {
      val aItem = iter2.next().asInstanceOf[AggregateItem]
      var idx = 0
      if (aItem.getAggregateSize > 0) {
        val iter3 = aItem.items()
        while (iter3.hasNext) {
          val item = iter3.next().asInstanceOf[VisualItem]
          if (item.isVisible) {
            addPoint(points, idx, item, hullMargin)
            idx += 2 * 4
          }
        }
        // if no aggregates are visible, do nothing
        if (idx > 0) {
          // compute convex hull
          val nHull = GraphicsLib.convexHull(points, idx)

          // prepare viz attribute array
          val fHull = {
            val prev = aItem.get(VisualItem.POLYGON).asInstanceOf[Array[Float]]
            if (prev == null || prev.length < nHull.length) {
              new Array[Float](nHull.length)
            } else if (prev.length > nHull.length) {
              prev(nHull.length) = Float.NaN
              prev
            } else {
              prev
            }
          }
          // copy hull values
          var j = 0
          while (j < nHull.length) {
            fHull(j) = nHull(j).toFloat
            j += 1
          }
          aItem.set(VisualItem.POLYGON, fHull)
          aItem.setValidated(false) // force invalidation
        }
      }
    }
  }
}