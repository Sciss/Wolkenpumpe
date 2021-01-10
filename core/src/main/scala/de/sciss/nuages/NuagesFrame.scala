/*
 *  NuagesFrame.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2021 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.lucre.Disposable
import de.sciss.lucre.synth.Txn
import de.sciss.nuages.impl.{FrameImpl => Impl}

import scala.swing.Frame

object NuagesFrame {
  def apply[T <: Txn[T]](view: NuagesView[T], undecorated: Boolean = false)(implicit tx: T): NuagesFrame[T] =
    Impl(view, undecorated = undecorated)
}
trait NuagesFrame[T <: Txn[T]] extends Disposable[T] {
  def view: NuagesView[T]
  def frame: Frame
}