package de.sciss.nuages
package impl

import scala.collection.immutable.{IndexedSeq => Vec}

final class NumericAdjustment(val angStart: Double, val valueStart: Vec[Double], var instant: Boolean) {
  var dragValue : Vec[Double] = valueStart
//  var isInit    : Boolean     = true
}