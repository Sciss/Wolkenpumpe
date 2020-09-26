/*
 *  PanelImplReact.scala
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
package impl

import de.sciss.lucre.{Ident, IdentMap, TxnLike, Txn => LTxn}
import de.sciss.lucre.synth.{AudioBus, Node, Synth, Txn}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.TSet

trait PanelImplReact[T <: Txn[T]] {
  _: NuagesPanel[T] =>

  import LTxn.peer

  // ---- abstract ----

  def deferVisTx(thunk: => Unit)(implicit tx: TxnLike): Unit

  protected def main: NuagesPanel[T]

  protected def nodeMap: IdentMap[T, NuagesObj[T]]

  protected def mkMonitor(bus: AudioBus, node: Node)(fun: Vec[Double] => Unit)(implicit tx: T): Synth

  // ---- impl ----

  private[this] val _nodeSet = TSet.empty[NuagesObj[T]]

  /** Disposes all registered nodes. Disposes `nodeMap`. */
  protected final def disposeNodes()(implicit tx: T): Unit = {
    _nodeSet.foreach(_.dispose())
    _nodeSet.clear()
    nodeMap.dispose()
  }

  final override def getNode(id: Ident[T])(implicit tx: T): Option[NuagesObj[T]] = nodeMap.get(id)

  final override def nodes(implicit tx: T): Set[NuagesObj[T]] = _nodeSet.snapshot

  final override def registerNode(id: Ident[T], view: NuagesObj[T])(implicit tx: T): Unit = {
    val ok = _nodeSet.add(view)
    if (!ok) throw new IllegalArgumentException(s"View $view was already registered")
    nodeMap.put(id, view)
  }

  final override def unregisterNode(id: Ident[T], view: NuagesObj[T])(implicit tx: T): Unit = {
    val ok = _nodeSet.remove(view)
    if (!ok) throw new IllegalArgumentException(s"View $view was not registered")
    nodeMap.remove(id)
  }
}