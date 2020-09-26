/*
 *  NuagesView.scala
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

import de.sciss.lucre.swing.View
import de.sciss.lucre.synth.Txn
import de.sciss.nuages.impl.{NuagesViewImpl => Impl}
import de.sciss.synth.proc.Universe

import scala.swing.Component

object NuagesView {
  def apply[T <: Txn[T]](nuages: Nuages[T], nuagesConfig: Nuages.Config)
                        (implicit tx: T, universe: Universe[T]): NuagesView[T] =
    Impl[T](nuages, nuagesConfig)
}
trait NuagesView[T <: Txn[T]] extends View.Cursor[T] {

  def panel: NuagesPanel[T]

  def controlPanel: ControlPanel

  def installFullScreenKey(p: scala.swing.RootPanel): Unit =
    throw new NotImplementedError()

  def addSouthComponent(c: Component): Unit
}