/*
 *  VisualScanImpl.scala
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
package impl

import java.awt.Graphics2D
import java.awt.event.MouseEvent
import java.awt.geom.Point2D

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, TxnLike}
import de.sciss.lucre.swing.requireEDT
import de.sciss.lucre.synth.{Node => SNode, Sys}
import de.sciss.nuages.NuagesAttribute.Input
import de.sciss.synth.proc.{AuralContext, AuralOutput, Output, Transport}
import prefuse.visual.VisualItem

import scala.concurrent.stm.TSet

object NuagesOutputImpl {
  def apply[S <: Sys[S]](parent: NuagesObj[S], output: Output[S], meter: Boolean)
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesOutputImpl[S] = {
    val res = new NuagesOutputImpl(parent, tx.newHandle(output), key = output.key, meter = meter)
    res.init(output)
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

  private[this] val mappingsSet = TSet.empty[Input[S]]
  private[this] val disposables = TSet.empty[Disposable[S#Tx]]

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

    if (meter) {
      val t = main.transport
      val obs = t.react { implicit tx => {
        case Transport.AuralStarted(_, auralContext) =>
        case _ =>
      }}
      disposables.add(obs)
      t.contextOption.foreach(auralStarted)
    }

    this
  }

  def dispose()(implicit tx: S#Tx): Unit = {
    disposables.foreach(_.dispose())
    disposables.clear()
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

  protected def boundsResized() = ()

  protected def renderDetail(g: Graphics2D, vi: VisualItem): Unit =
    drawName(g, vi, diam * vi.getSize.toFloat * 0.5f)

  private[this] def auralOutputAdded(auralOutput: AuralOutput[S])(implicit tx: S#Tx): Unit = {
    val bus   = auralOutput.bus
    val node  = auralOutput.view.nodeOption.fold[SNode](bus.server.defaultGroup)(_.node)
    val syn   = main.mkMeter(bus = bus, node = node)(parent.meterUpdate)
    disposables.add(syn)
  }

  private[this] def auralStarted(auralContext: AuralContext[S])(implicit tx: S#Tx): Unit = {
    val id = output.id
    auralContext.getAux[AuralOutput[S]](id).fold[Unit] {
      val obs = auralContext.observeAux[AuralOutput[S]](id) { implicit tx => {
        case AuralContext.AuxAdded(_, auralOutput) => auralOutputAdded(auralOutput)
        // case AuralContext.AuxRemoved(_, auralOutput) => auralOutputRemoved(auralOutput)
      }}
      disposables.add(obs)
    } (auralOutputAdded)
  }
}