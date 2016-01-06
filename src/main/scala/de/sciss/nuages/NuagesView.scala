/*
 *  NuagesView.scala
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

import de.sciss.lucre.stm
import de.sciss.lucre.swing.View
import de.sciss.lucre.synth.Sys
import de.sciss.synth.proc.{WorkspaceHandle, AuralSystem}
import impl.{NuagesViewImpl => Impl}

import scala.swing.Component

object NuagesView {
  def apply[S <: Sys[S]](nuages: Nuages[S], nuagesConfig: Nuages.Config, scissConfig: ScissProcs.Config)
                        (implicit tx: S#Tx, aural: AuralSystem, workspace: WorkspaceHandle[S],
                         cursor: stm.Cursor[S]): NuagesView[S] =
    Impl(nuages, nuagesConfig, scissConfig)
}
trait NuagesView[S <: Sys[S]] extends View.Cursor[S] {
  def panel: NuagesPanel[S]
  def controlPanel: ControlPanel

  def installFullScreenKey(frame: scala.swing.Window): Unit

  def addSouthComponent(c: Component): Unit
}