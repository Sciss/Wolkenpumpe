/*
 *  NuagesPanel.scala
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
import de.sciss.lucre.swing.View
import de.sciss.lucre.synth.Sys
import de.sciss.synth.proc.{WorkspaceHandle, AuralSystem, Transport}
import impl.{PanelImpl => Impl}
import prefuse.{Visualization, Display}

import scala.swing.Point

object NuagesPanel {
  var verbose = false

  private[nuages] val GROUP_GRAPH = "graph"
  private[nuages] val COL_NUAGES  = "nuages"

  final val masterAmpSpec       = ParamSpec(0.01, 10.0, ExpWarp) -> 1.0
  final val soloAmpSpec         = ParamSpec(0.10, 10.0, ExpWarp) -> 0.5

  def apply[S <: Sys[S]](nuages: Nuages[S], config: Nuages.Config, aural: AuralSystem)
                        (implicit tx: S#Tx, cursor: stm.Cursor[S], workspace: WorkspaceHandle[S]): NuagesPanel[S] =
    Impl(nuages, config, aural)
}
trait NuagesPanel[S <: Sys[S]] extends View[S] {
  def nuages(implicit tx: S#Tx): Nuages[S]

  def transport: Transport[S]

  def config : Nuages.Config

  def setMasterVolume(v: Double)(implicit tx: S#Tx): Unit
  def setSoloVolume  (v: Double)(implicit tx: S#Tx): Unit

  /** Must be called on the EDT */
  def display: Display

  /** Must be called on the EDT */
  def visualization: Visualization

  /** Must be called on the EDT */
  def showCreateGenDialog(pt: Point): Boolean

  /** Must be called on the EDT */
  def showCreateFilterDialog(vOut: VisualScan[S], vIn: VisualScan[S], pt: Point): Boolean
}