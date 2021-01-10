/*
 *  NumericAdjustment.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2021 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
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