/*
 *  Util.scala
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

object Util {
  /** Binary search on an indexed collection.
    *
    * @return  if positive: the position of elem in coll (i.e. elem is
    *          contained in coll). if negative: (-ins -1) where ins is the
    *          position at which elem should be inserted into the collection.
    */
  def binarySearch[A](coll: IndexedSeq[A], elem: A)(implicit ord: Ordering[A]): Int = {
    var index = 0
    var low = 0
    var high = coll.size - 1
    while ({
      index  = (high + low) >> 1
      low   <= high
    }) {
      val cmp = ord.compare(coll(index), elem)
      if (cmp == 0) return index
      if (cmp < 0) {
        low = index + 1
      } else {
        high = index - 1
      }
    }
    -low - 1
  }
}