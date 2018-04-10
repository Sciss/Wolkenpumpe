/*
 *  PanelImplReact.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2017 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.lucre.stm
import de.sciss.lucre.stm.TxnLike
import de.sciss.lucre.synth.{AudioBus, Node, Synth, Sys}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.TSet

trait PanelImplReact[S <: Sys[S]] {
  import TxnLike.peer

  // ---- abstract ----

  def deferVisTx(thunk: => Unit)(implicit tx: TxnLike): Unit

  protected def main: NuagesPanel[S]

  protected def nodeMap: stm.IdentifierMap[S#Id, S#Tx, NuagesObj[S]]

  protected def mkMonitor(bus: AudioBus, node: Node)(fun: Vec[Double] => Unit)(implicit tx: S#Tx): Synth

  // ---- impl ----

  private[this] val nodeSet = TSet.empty[NuagesObj[S]]

  /** Disposes all registered nodes. Disposes `nodeMap`. */
  protected final def disposeNodes()(implicit tx: S#Tx): Unit = {
    nodeSet.foreach(_.dispose())
    nodeSet.clear()
    nodeMap.dispose()
  }

  final def registerNode(id: S#Id, view: NuagesObj[S])(implicit tx: S#Tx): Unit = {
    val ok = nodeSet.add(view)
    if (!ok) throw new IllegalArgumentException(s"View $view was already registered")
    nodeMap.put(id, view)
  }

  final def unregisterNode(id: S#Id, view: NuagesObj[S])(implicit tx: S#Tx): Unit = {
    val ok = nodeSet.remove(view)
    if (!ok) throw new IllegalArgumentException(s"View $view was not registered")
    nodeMap.remove(id)
  }
}