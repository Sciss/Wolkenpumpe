/*
 *  VisualParam.scala
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

import de.sciss.lucre.stm.Sys

trait NuagesParam[S <: Sys[S]] extends NuagesData[S] {
  // ---- methods to be called on the EDT ----

  def parent: NuagesObj[S]

  /** The scan or attribute key in `parent` to point to this component. */
  def key: String
}
