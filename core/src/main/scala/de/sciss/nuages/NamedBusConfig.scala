/*
 *  NamedBusConfig.scala
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

import de.sciss.synth.UGenSource.Vec

object NamedBusConfig {
  /** This name can be used for buses that are present but for which no synths should created. */
  val Ignore = "ignore"
}
final case class NamedBusConfig(name: String, indices: Vec[Int] /* offset: Int, numChannels: Int */) {
  def stopOffset: Int = if (indices.isEmpty) 0 else indices.max + 1 // offset + numChannels

  def numChannels: Int = indices.size
}