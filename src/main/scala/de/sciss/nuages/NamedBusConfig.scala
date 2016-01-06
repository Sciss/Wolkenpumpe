/*
 *  NamedBusConfig.scala
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

package de.sciss.nuages

final case class NamedBusConfig(name: String, offset: Int, numChannels: Int) {
  def stopOffset: Int = offset + numChannels
}