/*
 *  package.scala
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

package de.sciss

import de.sciss.nuages.impl.NuagesImpl

package object nuages {
  def ExpWarp   = ExponentialWarp
  def LinWarp   = LinearWarp
  val TrigSpec  = ParamSpec(0, 1, IntWarp)

  def initTypes(): Unit = {
    ParamSpec .init()
    NuagesImpl.init()
  }
}
