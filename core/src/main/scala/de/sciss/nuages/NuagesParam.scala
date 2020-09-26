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

import de.sciss.lucre.Txn
import de.sciss.synth.proc.AuralObj

/** A common super-trait for both input views (`NuagesAttribute`)
  * and output views (`NuagesOutput`).
  */
trait NuagesParam[T <: Txn[T]] extends NuagesData[T] {
  // ---- methods to be called on the EDT ----

  def parent: NuagesObj[T]

  /** The scan or attribute key in `parent` to point to this component. */
  def key: String

  def auralObjAdded  (aural: AuralObj.Proc[T])(implicit tx: T): Unit
  def auralObjRemoved(aural: AuralObj.Proc[T])(implicit tx: T): Unit
}