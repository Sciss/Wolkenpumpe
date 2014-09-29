/*
 *  NuagesFrame.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2014 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.lucre.stm
import de.sciss.lucre.synth.Sys
import impl.{FrameImpl => Impl}

object NuagesFrame {
  def apply[S <: Sys[S]](panel: NuagesPanel[S])(implicit tx: S#Tx, cursor: stm.Cursor[S]): NuagesFrame[S] =
    Impl(panel)
}
trait NuagesFrame[S <: Sys[S]] {
  def view: NuagesPanel[S]
}