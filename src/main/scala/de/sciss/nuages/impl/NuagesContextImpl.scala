/*
 *  NuagesContextImpl.scala
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
package impl

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, IdentifierMap, Sys}
import de.sciss.nuages.NuagesContext.{AuxAdded, AuxUpdate}

// XXX TODO --- DRY with AuralContextImpl
object NuagesContextImpl {
  def apply[S <: Sys[S]](implicit tx: S#Tx): NuagesContext[S] = {
    val auxMap  = tx.newInMemoryIDMap[Any]
    val obsMap  = tx.newInMemoryIDMap[List[AuxObserver[S]]]
    val res     = new Impl[S](auxMap, obsMap)
    res
  }

  private trait AuxObserver[S <: Sys[S]]
    extends Disposable[S#Tx] {

    def fun: S#Tx => AuxUpdate[S, Any] => Unit
  }

  private final class Impl[S <: Sys[S]](auxMap: IdentifierMap[S#ID, S#Tx, Any],
                                        auxObservers: IdentifierMap[S#ID, S#Tx, List[AuxObserver[S]]])
    extends NuagesContext[S] {

    private final class AuxObserverImpl(idH: stm.Source[S#Tx, S#ID],
                                        val fun: S#Tx => AuxUpdate[S, Any] => Unit)
      extends AuxObserver[S] {

      def dispose()(implicit tx: S#Tx): Unit = {
        val id    = idH()
        val list0 = auxObservers.getOrElse(id, Nil)
        val list1 = list0.filterNot(_ == this)
        if (list1.isEmpty) auxObservers.remove(id) else auxObservers.put(id, list1)
      }
    }

    def observeAux[A](id: S#ID)(fun: S#Tx => AuxUpdate[S, A] => Unit)(implicit tx: S#Tx): Disposable[S#Tx] = {
      val list0 = auxObservers.getOrElse(id, Nil)
      val obs = new AuxObserverImpl(tx.newHandle(id), fun.asInstanceOf[S#Tx => AuxUpdate[S, Any] => Unit])
      val list1 = obs :: list0
      auxObservers.put(id, list1)
      obs
    }

    def putAux[A](id: S#ID, value: A)(implicit tx: S#Tx): Unit = {
      auxMap.put(id, value)
      implicit val itx = tx.peer
      val list = auxObservers.getOrElse(id, Nil)
      if (list.nonEmpty) {
        val upd = AuxAdded(id, value)
        list.foreach { obs =>
          obs.fun(tx)(upd)
        }
      }
    }

    def getAux[A](id: S#ID)(implicit tx: S#Tx): Option[A] = auxMap.get(id).asInstanceOf[Option[A]]

    def removeAux(id: S#ID)(implicit tx: S#Tx): Unit = {
      auxMap.remove(id)
    }
  }
}