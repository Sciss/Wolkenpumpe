/*
 *  NuagesPanel.scala
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

import java.awt.geom.Point2D

import de.sciss.lucre.stm.{Obj, Sys, TxnLike}
import de.sciss.lucre.swing.View
import de.sciss.lucre.synth.{AudioBus, Synth, Txn, Node => SNode, Sys => SSys}
import de.sciss.nuages.impl.{PanelImpl => Impl}
import de.sciss.synth.proc.{Output, Transport, Universe}
import javax.swing.BoundedRangeModel
import prefuse.data.Graph
import prefuse.visual.{AggregateTable, VisualGraph}
import prefuse.{Display, Visualization}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.swing.Point

object NuagesPanel {
  var verbose = false

  private[nuages] val GROUP_GRAPH     = "graph"
  private[nuages] val COL_NUAGES      = "nuages"
  private[nuages] val GROUP_SELECTION = "sel"

  final val GLIDE_KEY = "key"

  final val mainAmpSpec : (ParamSpec, Double) = ParamSpec(0.01, 10.0, ExponentialWarp) -> 1.0
  final val soloAmpSpec : (ParamSpec, Double) = ParamSpec(0.10, 10.0, ExponentialWarp) -> 0.5

  def apply[S <: SSys[S]](nuages: Nuages[S], config: Nuages.Config)
                        (implicit tx: S#Tx, universe: Universe[S], context: NuagesContext[S]): NuagesPanel[S] =
    Impl(nuages, config)
}
trait NuagesPanel[S <: Sys[S]] extends View.Cursor[S] {

  implicit val universe: Universe[S]

  def transport: Transport[S]

  def config : Nuages.Config

  implicit def context: NuagesContext[S]

  def isTimeline: Boolean

  // ---- methods to be called on the EDT ----

  // -- prefuse --

  def display         : Display
  def visualization   : Visualization
  def graph           : Graph
  def visualGraph     : VisualGraph
  def aggregateTable  : AggregateTable

  // -- dialogs --

  def showCreateGenDialog(pt: Point): Boolean
  def showInsertFilterDialog(vOut: NuagesOutput[S], vIn: NuagesAttribute[S], pt: Point): Boolean
  def showAppendFilterDialog(vOut: NuagesOutput[S], pt: Point): Boolean
  def showInsertMacroDialog(): Boolean

  def showOverlayPanel(p: OverlayPanel, pt: Option[Point] = None): Boolean

  def setSolo(vp: NuagesObj[S], onOff: Boolean)(implicit tx: S#Tx): Unit

  def selection: Set[NuagesNode[S]]

  def saveMacro(name: String, obj: Set[NuagesObj[S]]): Unit

  // -- gliding --

  // XXX TODO -- cheesy hack

  /** Glide time normalised to 0..1 */
  var glideTime       : Float
  def glideTimeModel  : BoundedRangeModel
  /** Used internally to indicate if driven by tablet or keys (`"key"`). */
  var glideTimeSource : String

  /** Whether glide time should be used or set */
  var acceptGlideTime : Boolean

  // ---- transactional methods ----

  def nuages(implicit tx: S#Tx): Nuages[S]

  def setMainVolume(v: Double)(implicit tx: S#Tx): Unit
  def setSoloVolume(v: Double)(implicit tx: S#Tx): Unit

  def mainSynth(implicit tx: Txn): Option[Synth]
  def mainSynth_=(value: Option[Synth])(implicit tx: Txn): Unit

  def mkPeakMeter (bus: AudioBus, node: SNode)(fun: Double      => Unit)(implicit tx: S#Tx): Synth
  def mkValueMeter(bus: AudioBus, node: SNode)(fun: Vec[Double] => Unit)(implicit tx: S#Tx): Synth
  def mkSoloSynth (bus: AudioBus, node: SNode)                          (implicit tx: S#Tx): Synth

  /** Schedule code to be executed during paused visualization animation
    * on the EDT after the commit of the transaction.
    */
  def deferVisTx(thunk: => Unit)(implicit tx: TxnLike): Unit

  def createGenerator(gen: Obj[S], colOpt: Option[Obj[S]], pt: Point2D)(implicit tx: S#Tx): Obj[S]

  def registerNode  (id: S#Id, view: NuagesObj[S])(implicit tx: S#Tx): Unit
  def unregisterNode(id: S#Id, view: NuagesObj[S])(implicit tx: S#Tx): Unit

  def getNode(id: S#Id)(implicit tx: S#Tx): Option[NuagesObj[S]] = throw new NotImplementedError()

  def nodes(implicit tx: S#Tx): Set[NuagesObj[S]] = throw new NotImplementedError()

  def appendFilter(pred: Output[S], fltSrc: Obj[S], colSrcOpt: Option[Obj[S]], fltPt: Point2D)
                  (implicit tx: S#Tx): Obj[S]

  def insertFilter(pred: Output[S], succ: NuagesAttribute[S], fltSrc: Obj[S], fltPt: Point2D)
                  (implicit tx: S#Tx): Obj[S]

  def prepareAndLocate(proc: Obj[S], pt: Point2D)(implicit tx: S#Tx): Unit =
    throw new NotImplementedError()

  def addNewObject(obj: Obj[S])(implicit tx: S#Tx): Unit = throw new NotImplementedError()
}