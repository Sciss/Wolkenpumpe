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

import prefuse.action.layout.Layout
import prefuse.visual.{AggregateItem, VisualItem}
import prefuse.util.GraphicsLib

/**
 *    Scala version of the AggregateLayout by Jeffrey Heer
 *    (Prefuse Demos). 
 */
object PrefuseAggregateLayout {
   private val hullMargin = 5

   private def addPoint( pts: Array[ Double ], idx: Int, item: VisualItem, growth: Int ) {
      val b          = item.getBounds
      val minX       = b.getMinX - growth
      val minY       = b.getMinY - growth
      val maxX       = b.getMaxX + growth
      val maxY       = b.getMaxY + growth
      pts( idx )     = minX
      pts( idx + 1 ) = minY
      pts( idx + 2 ) = minX
      pts( idx + 3 ) = maxY
      pts( idx + 4 ) = maxX
      pts( idx + 5 ) = minY
      pts( idx + 6 ) = maxX
      pts( idx + 7 ) = maxY
   }
}

class PrefuseAggregateLayout( aggrGroup: String ) extends Layout( aggrGroup ) {
   import PrefuseAggregateLayout._

   private var points = new Array[ Double ]( 8 * 4 ) // buffer for computing convex hulls

   def run( frac: Double ) {
      val aggr = m_vis.getGroup( aggrGroup ) // .asInstanceOf[ AggregateTable ]
      if( aggr.getTupleCount == 0 ) return // do we have any to process?

      // update buffers
      var maxsz = 0
      val iter1 = aggr.tuples()
      while( iter1.hasNext ) {
         val item = iter1.next().asInstanceOf[ AggregateItem ]
         maxsz = math.max( maxsz, 4 * 2 * item.getAggregateSize )
      }
      if( maxsz > points.length ) {
         points = new Array[ Double ]( maxsz + 8 )
      }

      // compute and assign convex hull for each aggregate
      val iter2 = m_vis.visibleItems( aggrGroup )
      while( iter2.hasNext ) {
         val aitem = iter2.next().asInstanceOf[ AggregateItem ]
         var idx = 0
         if( aitem.getAggregateSize > 0 ) {
            val iter3 = aitem.items()
            while( iter3.hasNext ) {
               val item = iter3.next().asInstanceOf[ VisualItem ]
               if( item.isVisible ) {
                  addPoint( points, idx, item, hullMargin )
                  idx += 2 * 4
               }
            }
            // if no aggregates are visible, do nothing
            if( idx > 0 ) {
               // compute convex hull
               val nhull = GraphicsLib.convexHull( points, idx )

               // prepare viz attribute array
               val fhull = {
                  val prev = aitem.get( VisualItem.POLYGON ).asInstanceOf[ Array[ Float ]]
                  if( prev == null || prev.length < nhull.length ) {
                      new Array[ Float ]( nhull.length )
                  } else if( prev.length > nhull.length ) {
                     prev( nhull.length ) = Float.NaN
                     prev
                  } else {
                     prev
                  }
               }
               // copy hull values
               var j = 0; while( j < nhull.length ) {
                  fhull( j ) = nhull( j ).toFloat
                  j += 1
               }
               aitem.set( VisualItem.POLYGON, fhull )
               aitem.setValidated( false ) // force invalidation
            }
         }
      }
   }
}