/*
 *  VisualScanImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2019 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
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
import de.sciss.lucre.swing.requireEDT
import de.sciss.lucre.synth.{AudioBus, Synth, Sys, Txn, Node => SNode}
import de.sciss.nuages.NuagesAttribute.Input
import de.sciss.nuages.impl.NuagesOutputImpl.Meter
import de.sciss.synth.proc.{AuralObj, AuralOutput, Output}
import prefuse.visual.VisualItem

import scala.concurrent.stm.{Ref, TSet}

object NuagesOutputImpl {
  def apply[S <: Sys[S]](parent: NuagesObj[S], output: Output[S], meter: Boolean)
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesOutputImpl[S] = {
    val res = new NuagesOutputImpl(parent, tx.newHandle(output), key = output.key, meter = meter)
    res.init(output)
  }

  final class Meter(val bus: AudioBus, val synth: Synth) extends Disposable[Txn] {
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
  private[this] val auralObserverRef  = Ref(Disposable.empty[S#Tx])
  private[this] val meterRef          = Ref(Option.empty[Meter])

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

  private def init(output: Output[S])(implicit tx: S#Tx): this.type = {
    main.deferVisTx(initGUI())      // IMPORTANT: first
    context.putAux[NuagesOutput[S]](output.id, this)
    this
  }

  def auralObjAdded  (aural: AuralObj.Proc[S])(implicit tx: S#Tx): Unit = if (meter) {
    aural.getOutput(key).foreach(mkMeter)
    val obs = aural.ports.react { implicit tx => {
      case AuralObj.Proc.OutputAdded  (_, auralOutput)  => mkMeter(auralOutput)
      case AuralObj.Proc.OutputRemoved(_, _)            => disposeMeter()
      case _ =>
    }}
    auralObserverRef.swap(obs).dispose()
  }

  def auralObjRemoved(aural: AuralObj.Proc[S])(implicit tx: S#Tx): Unit = if (meter) {
    disposeMeter()
    disposeAuralObserver()
  }

  def dispose()(implicit tx: S#Tx): Unit = {
    if (meter) {
      disposeMeter()
      disposeAuralObserver()
    }
    mappingsSet.clear()
    context.removeAux(output.id)
    main.deferVisTx(disposeGUI())
  }

  private[this] def initGUI(): Unit = {
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

  def meterOption(implicit tx: TxnLike): Option[Meter] = meterRef()

  private[this] def mkMeter(auralOutput: AuralOutput[S])(implicit tx: S#Tx): Unit = {
    val bus   = auralOutput.bus
    val node  = auralOutput.view.nodeOption.fold[SNode](bus.server.defaultGroup)(_.node)
    val syn   = main.mkPeakMeter(bus = bus, node = node)(parent.meterUpdate)
    val m     = new Meter(bus, syn)
    meterRef.swap(Some(m)).foreach(_.dispose())
  }

  private[this] def disposeMeter()(implicit tx: S#Tx): Unit =
    meterRef.swap(None).foreach(_.dispose())

  private[this] def disposeAuralObserver()(implicit tx: S#Tx): Unit =
    auralObserverRef.swap(Disposable.empty).dispose()
}