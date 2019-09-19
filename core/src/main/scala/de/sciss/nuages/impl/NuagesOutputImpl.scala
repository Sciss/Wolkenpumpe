/*
 *  VisualScanImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2019 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import java.awt.Graphics2D
import java.awt.event.MouseEvent
import java.awt.geom.Point2D

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, TxnLike}
import de.sciss.lucre.swing.LucreSwing.requireEDT
import de.sciss.lucre.synth.{AudioBus, Synth, Sys, Txn, Node => SNode}
import de.sciss.nuages.NuagesAttribute.Input
import de.sciss.nuages.NuagesOutput.Meter
import de.sciss.nuages.impl.NuagesOutputImpl.MeterImpl
import de.sciss.synth.proc.{AuralObj, AuralOutput, Output}
import prefuse.visual.VisualItem

import scala.concurrent.stm.{Ref, TSet}

object NuagesOutputImpl {
  def apply[S <: Sys[S]](parent: NuagesObj[S], output: Output[S], meter: Boolean)
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesOutputImpl[S] = {
    val res = new NuagesOutputImpl(parent, tx.newHandle(output), key = output.key, meter = meter)
    res.init(output)
  }

  private final class MeterImpl(val bus: AudioBus, val synth: Synth) extends NuagesOutput.Meter with Disposable[Txn] {
    def dispose()(implicit tx: Txn): Unit = synth.dispose()
  }
}
final class NuagesOutputImpl[S <: Sys[S]] private(val parent: NuagesObj[S],
                                                  val outputH: stm.Source[S#Tx, Output[S]],
                                                  val key: String, meter: Boolean)
                                                 (implicit context: NuagesContext[S])
  extends NuagesParamRootImpl[S] with NuagesOutput[S] {

  import NuagesDataImpl._
  import TxnLike.peer

  override def toString = s"NuagesOutput($parent, $key)"

  protected def nodeSize = 0.333333f

  private[this] val mappingsSet       = TSet.empty[Input[S]]
  private[this] val auralRef          = Ref(Option.empty[AuralObj.Proc[S]])
  private[this] val auralObserverRef  = Ref(Disposable.empty[S#Tx])
  private[this] val meterRef          = Ref(Option.empty[MeterImpl])
  private[this] val soloRef           = Ref(Option.empty[Synth])
  private[this] val _soloed           = Ref(false)

  def output(implicit tx: S#Tx): Output[S] = outputH()

  def mappings(implicit tx: S#Tx): Set[Input[S]] = mappingsSet.snapshot

  def addMapping(view: Input[S])(implicit tx: S#Tx): Unit = {
    val res = mappingsSet.add(view)
    if (!res) throw new IllegalArgumentException(s"View $view was already registered")
  }

  def removeMapping(view: Input[S])(implicit tx: S#Tx): Unit = {
    // Note: we call `removeAux` from `dispose`, that is after `mappingsSet.clear()`,
    // so we must expect calls to `removeMapping` that do not find that view any longer.
    // Therefore we don't require that the `view` be found here.

    /* val res = */ mappingsSet.remove(view)
    // if (!res) throw new IllegalArgumentException(s"View $view was not registered")
  }

  def meterOption(implicit tx: S#Tx): Option[Meter] = meterRef()

  def setSolo(onOff: Boolean)(implicit tx: S#Tx): Unit =
    if (_soloed.swap(onOff) != onOff) {
      if (onOff) {
        for {
          aural       <- auralRef()
          auralOutput <- aural.getOutput(key)
        } {
          mkSolo(auralOutput)
        }
      } else {
        disposeSolo()
      }
    }

  private def init(output: Output[S])(implicit tx: S#Tx): this.type = {
    main.deferVisTx(initGUI())      // IMPORTANT: first
    context.putAux[NuagesOutput[S]](output.id, this)
    this
  }

  private def setAuralOutput(auralOutput: AuralOutput[S])(implicit tx: S#Tx): Unit = {
    if (meter)      mkMeter (auralOutput)
    if (_soloed())  mkSolo  (auralOutput)
  }

  private def disposeMeterAndSolo()(implicit tx: S#Tx): Unit = {
    disposeMeter()
    disposeSolo ()
  }

  def auralObjAdded  (aural: AuralObj.Proc[S])(implicit tx: S#Tx): Unit = {
    aural.getOutput(key).foreach(setAuralOutput(_))
    val obs = aural.ports.react { implicit tx => {
      case AuralObj.Proc.OutputAdded  (_, auralOutput)  => setAuralOutput(auralOutput)
      case AuralObj.Proc.OutputRemoved(_, _)            => disposeMeterAndSolo()
      case _ =>
    }}
    auralObserverRef.swap(obs).dispose()
    auralRef() = Some(aural)
  }

  def auralObjRemoved(aural: AuralObj.Proc[S])(implicit tx: S#Tx): Unit = if (meter) {
    disposeMeterAndSolo()
    disposeAuralObserver()
    auralRef() = None
  }

  def dispose()(implicit tx: S#Tx): Unit = {
    disposeMeterAndSolo()
    disposeAuralObserver()
    mappingsSet.clear()
    context.removeAux(output.id)
    main.deferVisTx(disposeGUI())
  }

  private def initGUI(): Unit = {
    requireEDT()
    mkPNodeAndEdge()
  }

  override def itemPressed(vi: VisualItem, e: MouseEvent, pt: Point2D): Boolean = (key != "in") && {
    // println("itemPressed")
    if (e.getClickCount == 2) {
      parent.main.showAppendFilterDialog(this, e.getPoint)
    }
    true
  }

  protected def boundsResized(): Unit = ()

  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit =
    drawName(g, vi, diam * vi.getSize.toFloat * 0.5f)

  private def getBusAndNode(auralOutput: AuralOutput[S])(implicit tx: S#Tx): (AudioBus, SNode) = {
    val bus   = auralOutput.bus
    val node  = auralOutput.view.nodeOption.fold[SNode](bus.server.defaultGroup)(_.node)
    (bus, node)
  }

  private def mkMeter(auralOutput: AuralOutput[S])(implicit tx: S#Tx): Unit = {
    val (bus, node) = getBusAndNode(auralOutput)
    val syn = main.mkPeakMeter(bus = bus, node = node)(parent.meterUpdate)
    val m   = new MeterImpl(bus, syn)
    meterRef.swap(Some(m)).foreach(_.dispose())
  }

  private def mkSolo(auralOutput: AuralOutput[S])(implicit tx: S#Tx): Unit = {
    val (bus, node) = getBusAndNode(auralOutput)
    val syn = main.mkSoloSynth(bus = bus, node = node)
    soloRef.swap(Some(syn)).foreach(_.dispose())
  }

  private def disposeMeter()(implicit tx: S#Tx): Unit =
    meterRef.swap(None).foreach(_.dispose())

  private def disposeSolo()(implicit tx: S#Tx): Unit =
    soloRef.swap(None).foreach(_.dispose())

  private def disposeAuralObserver()(implicit tx: S#Tx): Unit =
    auralObserverRef.swap(Disposable.empty).dispose()
}