/*
 *  NuagesContext.scala
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

import de.sciss.lucre.stm.{Disposable, Sys}
import de.sciss.nuages.impl.{NuagesContextImpl => Impl}

object NuagesContext {
  def apply[S <: Sys[S]](implicit tx: S#Tx): NuagesContext[S] = Impl[S]

  sealed trait AuxUpdate[S <: Sys[S], +A]
  final case class AuxAdded[S <: Sys[S], A](id: S#ID, value: A) extends AuxUpdate[S, A]
}
trait NuagesContext[S <: Sys[S]] {
  import NuagesContext.AuxUpdate

  def putAux[A](id: S#ID, value: A)(implicit tx: S#Tx): Unit

  def getAux[A](id: S#ID)(implicit tx: S#Tx): Option[A]

  /** Waits for the auxiliary object to appear. If the object
    * appears the function is applied, otherwise nothing happens.
    */
  def observeAux[A](id: S#ID)(fun: S#Tx => AuxUpdate[S, A] => Unit)(implicit tx: S#Tx): Disposable[S#Tx]

  def removeAux(id: S#ID)(implicit tx: S#Tx): Unit
}
