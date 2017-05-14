/*
 *  package.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2017 Hanns Holger Rutz. All rights reserved.
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

  var showLog     = false
  var showAggrLog = false

  // var AGGR_LOCK   = false

  @elidable(CONFIG) private[nuages] def log(what: => String): Unit =
    if (showLog) println(s"${logHeader.format(new Date())}$what")


  @elidable(CONFIG) private[nuages] def logAggr(what: => String): Unit =
    if (showAggrLog) {
      // require(AGGR_LOCK)
      Console.out.println(s"${logHeader.format(new Date())} aggr $what")
    }

  /** Exception are sometimes swallowed without printing in a transaction. This ensures a print. */
  def ???! : Nothing = {
    new Exception("???").printStackTrace()
    throw new NotImplementedError
  }
}