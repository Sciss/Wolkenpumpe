/*
 *  VisualParam.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2020 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.lucre.stm.Sys
import de.sciss.synth.proc.AuralObj

/** A common super-trait for both input views (`NuagesAttribute`)
  * and output views (`NuagesOutput`).
  */
trait NuagesParam[S <: Sys[S]] extends NuagesData[S] {
  // ---- methods to be called on the EDT ----

  def parent: NuagesObj[S]

  /** The scan or attribute key in `parent` to point to this component. */
  def key: String

  def auralObjAdded  (aural: AuralObj.Proc[S])(implicit tx: S#Tx): Unit
  def auralObjRemoved(aural: AuralObj.Proc[S])(implicit tx: S#Tx): Unit
}