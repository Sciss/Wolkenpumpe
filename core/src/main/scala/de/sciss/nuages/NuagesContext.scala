/*
 *  NuagesContext.scala
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

import de.sciss.lucre.{Disposable, Ident, Txn}
import de.sciss.nuages.impl.{NuagesContextImpl => Impl}

object NuagesContext {
  def apply[T <: Txn[T]](implicit tx: T): NuagesContext[T] = Impl[T]

  sealed trait AuxUpdate[T <: Txn[T], +A] {
    def id: Ident[T]
  }
  final case class AuxAdded  [T <: Txn[T], A](id: Ident[T], value: A) extends AuxUpdate[T, A]
  final case class AuxRemoved[T <: Txn[T]   ](id: Ident[T]          ) extends AuxUpdate[T, Nothing]
}
trait NuagesContext[T <: Txn[T]] {
  import NuagesContext.AuxUpdate

  def putAux[A](id: Ident[T], value: A)(implicit tx: T): Unit

  def getAux[A](id: Ident[T])(implicit tx: T): Option[A]

  /** Waits for the auxiliary object to appear. If the object
    * appears the function is applied, otherwise nothing happens.
    */
  def observeAux[A](id: Ident[T])(fun: T => AuxUpdate[T, A] => Unit)(implicit tx: T): Disposable[T]

  def removeAux(id: Ident[T])(implicit tx: T): Unit
}
