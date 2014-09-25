/*
 *  NuagesPanel.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2013 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either
 *  version 2, june 1991 of the License, or (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public
 *  License (gpl.txt) along with this software; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.lucre.stm
import de.sciss.lucre.swing.View
import de.sciss.lucre.synth.Sys
import impl.{PanelImpl => Impl}
import prefuse.{Visualization, Display}

object NuagesPanel {
  var verbose = false

  private[nuages] val GROUP_GRAPH         = "graph"
  private[nuages] val COL_NUAGES          = "nuages"

  final val masterAmpSpec       = ParamSpec(0.01, 10.0, ExpWarp) -> 1.0
  final val soloAmpSpec         = ParamSpec(0.10, 10.0, ExpWarp) -> 0.5

  def apply[S <: Sys[S]](config: Nuages.Config)(implicit tx: S#Tx, cursor: stm.Cursor[S]): NuagesPanel[S] =
    Impl(config)
}
trait NuagesPanel[S <: Sys[S]] extends View[S] {
  def config : Nuages.Config

  /** Must be called on the EDT */
  def display: Display

  /** Must be called on the EDT */
  def visualization: Visualization
}