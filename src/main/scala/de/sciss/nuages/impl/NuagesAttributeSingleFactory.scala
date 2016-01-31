/*
 *  NuagesAttributeSingleFactory.scala
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
package impl

import de.sciss.lucre.synth.Sys
import de.sciss.nuages.NuagesAttribute.Input

trait NuagesAttributeSingleFactory extends NuagesAttribute.Factory {
  final def tryConsume[S <: Sys[S]](oldInput: Input[S], /* newOffset: Long, */ newValue: Repr[S])
                                   (implicit tx: S#Tx, context: NuagesContext[S]): Option[Input[S]] = None
}
