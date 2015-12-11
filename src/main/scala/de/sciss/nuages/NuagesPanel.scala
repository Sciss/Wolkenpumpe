/*
 *  NuagesPanel.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2015 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import java.awt.geom.Point2D

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Sys, Obj, TxnLike}
import de.sciss.lucre.swing.View
import de.sciss.lucre.synth.{Synth, Sys => SSys, Txn}
import de.sciss.nuages.impl.{PanelImpl => Impl}
import de.sciss.synth.proc.{AuralSystem, Transport, WorkspaceHandle}
import prefuse.data.Graph
import prefuse.visual.{AggregateTable, VisualGraph}
import prefuse.{Display, Visualization}

import scala.swing.Point

object NuagesPanel {
  var verbose = false

  private[nuages] val GROUP_GRAPH     = "graph"
  private[nuages] val COL_NUAGES      = "nuages"
  private[nuages] val GROUP_SELECTION = "sel"

  final val masterAmpSpec       = ParamSpec(0.01, 10.0, ExponentialWarp) -> 1.0
  final val soloAmpSpec         = ParamSpec(0.10, 10.0, ExponentialWarp) -> 0.5

  def apply[S <: SSys[S]](nuages: Nuages[S], config: Nuages.Config)
                        (implicit tx: S#Tx, aural: AuralSystem,
                         cursor: stm.Cursor[S], workspace: WorkspaceHandle[S],
                         context: NuagesContext[S]): NuagesPanel[S] =
    Impl(nuages, config)
}
trait NuagesPanel[S <: Sys[S]] extends View[S] {

  def aural: AuralSystem

  def transport: Transport[S]

  def cursor: stm.Cursor[S]

  def config : Nuages.Config

  implicit def context: NuagesContext[S]

  // ---- methods to be called on the EDT ----

  // -- prefuse --

  def display       : Display
  def visualization : Visualization
  def graph         : Graph
  def visualGraph   : VisualGraph
  def aggrTable     : AggregateTable

  // -- dialogs --

  def showCreateGenDialog(pt: Point): Boolean
  def showInsertFilterDialog(vOut: NuagesOutput[S], vIn: NuagesOutput[S], pt: Point): Boolean
  def showAppendFilterDialog(vOut: NuagesOutput[S], pt: Point): Boolean
  def showInsertMacroDialog(): Boolean

  def showOverlayPanel(p: OverlayPanel, pt: Option[Point] = None): Boolean

  def setSolo(vp: NuagesObj[S], onOff: Boolean): Unit

  def selection: Set[NuagesNode[S]]

  def saveMacro(name: String, obj: Set[NuagesObj[S]]): Unit

  // ---- transactional methods ----

  def nuages(implicit tx: S#Tx): Nuages[S]

  def setMasterVolume(v: Double)(implicit tx: S#Tx): Unit
  def setSoloVolume  (v: Double)(implicit tx: S#Tx): Unit

  def masterSynth(implicit tx: Txn): Option[Synth]
  def masterSynth_=(value: Option[Synth])(implicit tx: Txn): Unit

  /** Schedule code to be executed during paused visualization animation
    * on the EDT after the commit of the transaction.
    */
  def deferVisTx(thunk: => Unit)(implicit tx: TxnLike): Unit

  def createGenerator(gen: Obj[S], colOpt: Option[Obj[S]], pt: Point2D)(implicit tx: S#Tx): Unit

  // SCAN
//  def insertFilter(pred: Scan[S], succ: Scan[S], flt: Obj[S], pt: Point2D)(implicit tx: S#Tx): Unit
//  def appendFilter(pred: Scan[S], flt: Obj[S], colOpt: Option[Obj[S]], pt: Point2D)(implicit tx: S#Tx): Unit

  def nodeMap: stm.IdentifierMap[S#ID, S#Tx, NuagesObj [S]]

  // def scanMap: stm.IdentifierMap[S#ID, S#Tx, VisualScan[S]]

  //  def scanMapPut(id: S#ID, view: NuagesOutput[S])(implicit tx: S#Tx): Unit
  //  def scanMapGet(id: S#ID)(implicit tx: S#Tx): Option[NuagesOutput[S]]
  //  def scanMapRemove(id: S#ID)(implicit tx: S#Tx): Unit

//  /** Transaction local hack */
//  def waitForScanView(id: S#ID)(fun: VisualScan[S] => Unit)(implicit tx: S#Tx): Unit

  // ---- these are left-overs from refactoring, they should not go in public API ----

  // SCAN
//   def assignMapping(source: Scan[S], vSink: VisualControl[S])(implicit tx: S#Tx): Unit
}