/*
 *  NamedBusConfig.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2018 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.synth.UGenSource.Vec

final case class NamedBusConfig(name: String, indices: Vec[Int] /* offset: Int, numChannels: Int */) {
  def stopOffset: Int = if (indices.isEmpty) 0 else indices.max + 1 // offset + numChannels

  def numChannels: Int = indices.size
}