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

import java.text.SimpleDateFormat
import java.util.{Date, Locale}

import scala.annotation.elidable
import scala.annotation.elidable.CONFIG

package object nuages {
  def ExpWarp   = ExponentialWarp
  def LinWarp   = LinearWarp
  val TrigSpec  = ParamSpec(0, 1, IntWarp)

  private lazy val logHeader = new SimpleDateFormat("[d MMM yyyy, HH:mm''ss.SSS] 'Nuages' ", Locale.US)

  var showLog = true

  @elidable(CONFIG) private[nuages] def log(what: => String): Unit =
    if (showLog) println(logHeader.format(new Date()) + what)
}