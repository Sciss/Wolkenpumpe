/*
 *  NuagesContextImpl.scala
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

import de.sciss.lucre.{Disposable, Ident, IdentMap, Source, Txn}
import de.sciss.nuages.NuagesContext.{AuxAdded, AuxRemoved, AuxUpdate}

// XXX TODO --- DRY with AuralContextImpl
object NuagesContextImpl {
  def apply[T <: Txn[T]](implicit tx: T): NuagesContext[T] = {
    val auxMap  = tx.newIdentMap[Any]
    val obsMap  = tx.newIdentMap[List[AuxObserver[T]]]
    val res     = new Impl[T](auxMap, obsMap)
    res
  }

  private trait AuxObserver[T <: Txn[T]]
    extends Disposable[T] {

    def fun: T => AuxUpdate[T, Any] => Unit
  }

  private final class Impl[T <: Txn[T]](auxMap      : IdentMap[T, Any],
                                        auxObservers: IdentMap[T, List[AuxObserver[T]]])
    extends NuagesContext[T] {

    private final class AuxObserverImpl(idH: Source[T, Ident[T]],
                                        val fun: T => AuxUpdate[T, Any] => Unit)
      extends AuxObserver[T] {

      def dispose()(implicit tx: T): Unit = {
        val id    = idH()
        val list0 = auxObservers.getOrElse(id, Nil)
        val list1 = list0.filterNot(_ == this)
        if (list1.isEmpty) auxObservers.remove(id) else auxObservers.put(id, list1)
      }
    }

    def observeAux[A](id: Ident[T])(fun: T => AuxUpdate[T, A] => Unit)(implicit tx: T): Disposable[T] = {
      val list0 = auxObservers.getOrElse(id, Nil)
      val obs = new AuxObserverImpl(tx.newHandle(id), fun.asInstanceOf[T => AuxUpdate[T, Any] => Unit])
      val list1 = obs :: list0
      auxObservers.put(id, list1)
      obs
    }

    def putAux[A](id: Ident[T], value: A)(implicit tx: T): Unit = {
      auxMap.put(id, value)
      val list = auxObservers.getOrElse(id, Nil)
      if (list.nonEmpty) {
        val upd = AuxAdded(id, value)
        list.foreach { obs =>
          obs.fun(tx)(upd)
        }
      }
    }

    def getAux[A](id: Ident[T])(implicit tx: T): Option[A] = auxMap.get(id).asInstanceOf[Option[A]]

    def removeAux(id: Ident[T])(implicit tx: T): Unit = {
      auxMap.remove(id)
      val list = auxObservers.getOrElse(id, Nil)
      if (list.nonEmpty) {
        val upd = AuxRemoved(id)
        list.foreach { obs =>
          obs.fun(tx)(upd)
        }
      }
    }
  }
}