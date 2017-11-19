/*
 *  NuagesAttributeSingleFactory.scala
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

package de.sciss.nuages
package impl

import de.sciss.lucre.synth.Sys
import de.sciss.nuages.NuagesAttribute.Input

/** This includes all scalar views, such as `NuagesIntAttrInput`.
  * For simplicity, they don't consume. In a performance, we will
  * only ever "upgrade", e.g. from scalar to grapheme.
  */
trait NuagesAttributeSingleFactory extends NuagesAttribute.Factory {
  final def tryConsume[S <: Sys[S]](oldInput: Input[S], /* newOffset: Long, */ newValue: Repr[S])
                                   (implicit tx: S#Tx, context: NuagesContext[S]): Option[Input[S]] = None
}
