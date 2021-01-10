/*
 *  NuagesPanel.scala
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

import java.awt.geom.Point2D

import de.sciss.lucre.swing.View
import de.sciss.lucre.synth.{AudioBus, RT, Synth, Node => SNode}
import de.sciss.lucre.{Ident, Obj, Txn, TxnLike, synth}
import de.sciss.nuages.impl.{PanelImpl => Impl}
import de.sciss.proc.{Proc, Transport, Universe}
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

  def apply[T <: synth.Txn[T]](nuages: Nuages[T], config: Nuages.Config)
                        (implicit tx: T, universe: Universe[T], context: NuagesContext[T]): NuagesPanel[T] =
    Impl(nuages, config)
}
trait NuagesPanel[T <: Txn[T]] extends View.Cursor[T] {

  implicit val universe: Universe[T]

  def transport: Transport[T]

  def config : Nuages.Config

  implicit def context: NuagesContext[T]

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
  def showInsertFilterDialog(vOut: NuagesOutput[T], vIn: NuagesAttribute[T], pt: Point): Boolean
  def showAppendFilterDialog(vOut: NuagesOutput[T], pt: Point): Boolean
  def showInsertMacroDialog(): Boolean

  def showOverlayPanel(p: OverlayPanel, pt: Option[Point] = None): Boolean

  def setSolo(vp: NuagesObj[T], onOff: Boolean)(implicit tx: T): Unit

  def selection: Set[NuagesNode[T]]

  def saveMacro(name: String, obj: Set[NuagesObj[T]]): Unit

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

  def nuages(implicit tx: T): Nuages[T]

  def setMainVolume(v: Double)(implicit tx: T): Unit
  def setSoloVolume(v: Double)(implicit tx: T): Unit

  def mainSynth(implicit tx: RT): Option[Synth]
  def mainSynth_=(value: Option[Synth])(implicit tx: RT): Unit

  def mkPeakMeter (bus: AudioBus, node: SNode)(fun: Double      => Unit)(implicit tx: T): Synth
  def mkValueMeter(bus: AudioBus, node: SNode)(fun: Vec[Double] => Unit)(implicit tx: T): Synth
  def mkSoloSynth (bus: AudioBus, node: SNode)                          (implicit tx: T): Synth

  /** Schedule code to be executed during paused visualization animation
    * on the EDT after the commit of the transaction.
    */
  def deferVisTx(thunk: => Unit)(implicit tx: TxnLike): Unit

  def createGenerator(gen: Obj[T], colOpt: Option[Obj[T]], pt: Point2D)(implicit tx: T): Obj[T]

  def registerNode  (id: Ident[T], view: NuagesObj[T])(implicit tx: T): Unit
  def unregisterNode(id: Ident[T], view: NuagesObj[T])(implicit tx: T): Unit

  def getNode(id: Ident[T])(implicit tx: T): Option[NuagesObj[T]] = throw new NotImplementedError()

  def nodes(implicit tx: T): Set[NuagesObj[T]] = throw new NotImplementedError()

  def appendFilter(pred: Proc.Output[T], fltSrc: Obj[T], colSrcOpt: Option[Obj[T]], fltPt: Point2D)
                  (implicit tx: T): Obj[T]

  def insertFilter(pred: Proc.Output[T], succ: NuagesAttribute[T], fltSrc: Obj[T], fltPt: Point2D)
                  (implicit tx: T): Obj[T]

  def prepareAndLocate(proc: Obj[T], pt: Point2D)(implicit tx: T): Unit =
    throw new NotImplementedError()

  def addNewObject(obj: Obj[T])(implicit tx: T): Unit = throw new NotImplementedError()
}