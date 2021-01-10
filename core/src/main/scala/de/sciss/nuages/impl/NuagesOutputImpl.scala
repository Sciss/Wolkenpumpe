/*
 *  VisualScanImpl.scala
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
package impl

import java.awt.Graphics2D
import java.awt.event.MouseEvent
import java.awt.geom.Point2D

import de.sciss.lucre.swing.LucreSwing.requireEDT
import de.sciss.lucre.synth.{AudioBus, RT, Synth, Txn, Node => SNode}
import de.sciss.lucre.{Disposable, Source, Txn => LTxn}
import de.sciss.nuages.NuagesAttribute.Input
import de.sciss.nuages.NuagesOutput.Meter
import de.sciss.nuages.impl.NuagesOutputImpl.MeterImpl
import de.sciss.proc.{AuralObj, AuralOutput, Proc}
import prefuse.visual.VisualItem

import scala.concurrent.stm.{Ref, TSet}

object NuagesOutputImpl {
  def apply[T <: Txn[T]](parent: NuagesObj[T], output: Proc.Output[T], meter: Boolean)
                        (implicit tx: T, context: NuagesContext[T]): NuagesOutputImpl[T] = {
    val res = new NuagesOutputImpl(parent, tx.newHandle(output), key = output.key, meter = meter)
    res.init(output)
  }

  private final class MeterImpl(val bus: AudioBus, val synth: Synth) extends NuagesOutput.Meter with Disposable[RT] {
    def dispose()(implicit tx: RT): Unit = synth.dispose()
  }
}
final class NuagesOutputImpl[T <: Txn[T]] private(val parent: NuagesObj[T],
                                                  val outputH: Source[T, Proc.Output[T]],
                                                  val key: String, meter: Boolean)
                                                 (implicit context: NuagesContext[T])
  extends NuagesParamRootImpl[T] with NuagesOutput[T] {

  import LTxn.peer
  import NuagesDataImpl._

  override def toString = s"NuagesOutput($parent, $key)"

  protected def nodeSize = 0.333333f

  private[this] val mappingsSet       = TSet.empty[Input[T]]
  private[this] val auralRef          = Ref(Option.empty[AuralObj.Proc[T]])
  private[this] val auralObserverRef  = Ref(Disposable.empty[T])
  private[this] val meterRef          = Ref(Option.empty[MeterImpl])
  private[this] val soloRef           = Ref(Option.empty[Synth])
  private[this] val _soloed           = Ref(false)

  def output(implicit tx: T): Proc.Output[T] = outputH()

  def mappings(implicit tx: T): Set[Input[T]] = mappingsSet.snapshot

  def addMapping(view: Input[T])(implicit tx: T): Unit = {
    val res = mappingsSet.add(view)
    if (!res) throw new IllegalArgumentException(s"View $view was already registered")
  }

  def removeMapping(view: Input[T])(implicit tx: T): Unit = {
    // Note: we call `removeAux` from `dispose`, that is after `mappingsSet.clear()`,
    // so we must expect calls to `removeMapping` that do not find that view any longer.
    // Therefore we don't require that the `view` be found here.

    /* val res = */ mappingsSet.remove(view)
    // if (!res) throw new IllegalArgumentException(s"View $view was not registered")
  }

  def meterOption(implicit tx: T): Option[Meter] = meterRef()

  def setSolo(onOff: Boolean)(implicit tx: T): Unit =
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

  private def init(output: Proc.Output[T])(implicit tx: T): this.type = {
    main.deferVisTx(initGUI())      // IMPORTANT: first
    context.putAux[NuagesOutput[T]](output.id, this)
    this
  }

  private def setAuralOutput(auralOutput: AuralOutput[T])(implicit tx: T): Unit = {
    if (meter)      mkMeter (auralOutput)
    if (_soloed())  mkSolo  (auralOutput)
  }

  private def disposeMeterAndSolo()(implicit tx: T): Unit = {
    disposeMeter()
    disposeSolo ()
  }

  def auralObjAdded  (aural: AuralObj.Proc[T])(implicit tx: T): Unit = {
    aural.getOutput(key).foreach(setAuralOutput(_))
    val obs = aural.ports.react { implicit tx => {
      case AuralObj.Proc.OutputAdded  (_, auralOutput)  => setAuralOutput(auralOutput)
      case AuralObj.Proc.OutputRemoved(_, _)            => disposeMeterAndSolo()
      case _ =>
    }}
    auralObserverRef.swap(obs).dispose()
    auralRef() = Some(aural)
  }

  def auralObjRemoved(aural: AuralObj.Proc[T])(implicit tx: T): Unit = if (meter) {
    disposeMeterAndSolo()
    disposeAuralObserver()
    auralRef() = None
  }

  def dispose()(implicit tx: T): Unit = {
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

  private def getBusAndNode(auralOutput: AuralOutput[T])(implicit tx: T): (AudioBus, SNode) = {
    val bus   = auralOutput.bus
    val node  = auralOutput.view.nodeOption.fold[SNode](bus.server.defaultGroup)(_.node)
    (bus, node)
  }

  private def mkMeter(auralOutput: AuralOutput[T])(implicit tx: T): Unit = {
    val (bus, node) = getBusAndNode(auralOutput)
    val syn = main.mkPeakMeter(bus = bus, node = node)(parent.meterUpdate)
    val m   = new MeterImpl(bus, syn)
    meterRef.swap(Some(m)).foreach(_.dispose())
  }

  private def mkSolo(auralOutput: AuralOutput[T])(implicit tx: T): Unit = {
    val (bus, node) = getBusAndNode(auralOutput)
    val syn = main.mkSoloSynth(bus = bus, node = node)
    soloRef.swap(Some(syn)).foreach(_.dispose())
  }

  private def disposeMeter()(implicit tx: T): Unit =
    meterRef.swap(None).foreach(_.dispose())

  private def disposeSolo()(implicit tx: T): Unit =
    soloRef.swap(None).foreach(_.dispose())

  private def disposeAuralObserver()(implicit tx: T): Unit =
    auralObserverRef.swap(Disposable.empty).dispose()
}