/*
 *  ExprLikeType.scala
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

import de.sciss.lucre.{event => evt}
import de.sciss.lucre.event.Sys
import de.sciss.lucre.expr.{Type1, Expr}
import de.sciss.serial.{DataOutput, DataInput}

import scala.language.higherKinds

trait ExprLikeType[A, _Repr[S <: Sys[S]] <: Expr[S, A]]
  extends Type1[_Repr] {

  type Repr[S <: Sys[S]] = _Repr[S]

  // ---- abstract ----

  def readValue(in: DataInput): A
  def writeValue(value: A, out: DataOutput): Unit

  // implicit def varSerializer[S <: Sys[S]]: evt.Serializer[S, Repr[S] with Expr.Var[S, A]]

  // ---- public ----

  def newConst[S <: Sys[S]](value: A): Repr[S] with Expr.Const[S, A]

  // def newVar[S <: Sys[S]](init: Expr[S, A])(implicit tx: S#Tx): Expr.Var[S, A]

  def readConst[S <: Sys[S]](in: DataInput): Repr[S] with Expr.Const[S, A]

  // def readVar[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): Expr.Var[S, A]
}