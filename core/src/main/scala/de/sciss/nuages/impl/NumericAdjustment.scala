/*
 *  NumericAdjustment.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2019 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import scala.collection.immutable.{IndexedSeq => Vec}

final class NumericAdjustment(val angStart: Double, val valueStart: Vec[Double], var instant: Boolean) {
  var dragValue : Vec[Double] = valueStart
//  var isInit    : Boolean     = true
}