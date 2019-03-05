/*
 *  NuagesFrame.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2019 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.lucre.stm.Disposable
import de.sciss.lucre.synth.Sys
import de.sciss.nuages.impl.{FrameImpl => Impl}

import scala.swing.Frame

object NuagesFrame {
  def apply[S <: Sys[S]](view: NuagesView[S], undecorated: Boolean = false)(implicit tx: S#Tx): NuagesFrame[S] =
    Impl(view, undecorated = undecorated)
}
trait NuagesFrame[S <: Sys[S]] extends Disposable[S#Tx] {
  def view: NuagesView[S]
  def frame: Frame
}