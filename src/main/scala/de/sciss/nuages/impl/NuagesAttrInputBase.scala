/*
 *  NuagesAttrInputBase.scala
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

import de.sciss.lucre.stm.{TxnLike, Sys}
import de.sciss.nuages.NuagesAttribute.Parent

import scala.concurrent.stm.Ref

trait NuagesAttrInputBase[S <: Sys[S]] extends NuagesAttribute.Input[S] {
  import TxnLike.peer

  private[this] val parentRef = Ref.make[Parent[S]]

  final def inputParent                     (implicit tx: S#Tx): Parent[S]  = parentRef()
  final def inputParent_=(parent: Parent[S])(implicit tx: S#Tx): Unit       = parentRef() = parent

  protected final def isTimeline: Boolean = attribute.parent.main.isTimeline
}