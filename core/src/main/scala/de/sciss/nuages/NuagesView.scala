/*
 *  NuagesView.scala
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

import de.sciss.lucre.stm
import de.sciss.lucre.stm.WorkspaceHandle
import de.sciss.lucre.swing.View
import de.sciss.lucre.synth.Sys
import de.sciss.synth.proc.AuralSystem
import impl.{NuagesViewImpl => Impl}

import scala.swing.Component

object NuagesView {
  def apply[S <: Sys[S]](nuages: Nuages[S], nuagesConfig: Nuages.Config)
                        (implicit tx: S#Tx, aural: AuralSystem, workspace: WorkspaceHandle[S],
                         cursor: stm.Cursor[S]): NuagesView[S] =
    Impl[S](nuages, nuagesConfig)
}
trait NuagesView[S <: Sys[S]] extends View.Cursor[S] {
  def panel: NuagesPanel[S]
  def controlPanel: ControlPanel

  def installFullScreenKey(frame: scala.swing.Window): Unit

  def addSouthComponent(c: Component): Unit
}