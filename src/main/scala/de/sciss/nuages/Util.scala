/*
 *  Util.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2011 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either
 *  version 2, june 1991 of the License, or (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public
 *  License (gpl.txt) along with this software; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 *
 *
 *  Changelog:
 */

package de.sciss.nuages

object Util {
   /**
    *    Binary search on an indexed collection. Couldn't find this in
    *    the standard Scala library?! And java.util.Collections unfortunately
    *    requires a java.util.List... This is taken from the SuperCollider
    *    timebased code.
    *
    *    @return  if positive: the position of elem in coll (i.e. elem is
    *             contained in coll). if negative: (-ins -1) where ins is the
    *             position at which elem should be inserted into the collection. 
    */
   def binarySearch[ @specialized T ]( coll: IndexedSeq[ T ], elem: T )( implicit ord: Ordering[ T ]) : Int = {
      var index   = 0
      var low	   = 0
      var high	   = coll.size - 1
      while({
         index  = (high + low) >> 1
         low   <= high
      }) {
         val cmp = ord.compare( coll( index ), elem )
         if( cmp == 0 ) return index
         if( cmp < 0 ) {
            low = index + 1
         } else {
            high = index - 1
         }
      }
      -low - 1
   }
}