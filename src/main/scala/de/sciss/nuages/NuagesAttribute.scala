/*
 *  NuagesAttribute.scala
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

import de.sciss.lucre.stm.{Disposable, Sys, Obj}
import de.sciss.lucre.synth.{Synth, Sys => SSys}
import de.sciss.nuages.impl.{NuagesAttributeImpl => Impl}
import prefuse.data.{Node => PNode}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref
import scala.language.higherKinds

object NuagesAttribute {
  def apply[S <: SSys[S]](key: String, value: Obj[S], parent: NuagesObj[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute[S] =
    Impl(key, value, parent)

  def tryApply[S <: SSys[S]](key: String, value: Obj[S], parent: NuagesObj[S])
                           (implicit tx: S#Tx, context: NuagesContext[S]): Option[NuagesAttribute[S]] =
    Impl.tryApply(key, value, parent)

    // ---- Factory ----

  trait Factory {
    def typeID: Int

    type Repr[~ <: Sys[~]] <: Obj[~]

    def apply[S <: SSys[S]](key: String, value: Repr[S], attr: NuagesAttribute[S])
                          (implicit tx: S#Tx, context: NuagesContext[S]): Input[S]
  }

  def addFactory(f: Factory): Unit = Impl.addFactory(f)

  def factories: Iterable[Factory] = Impl.factories

  // ----

  trait Mapping[S <: Sys[S]] {
    /** The metering synth that via `SendTrig` updates the control's current value. */
    def synth: Ref[Option[Synth]]

    var source: Option[NuagesOutput[S]]
  }

  trait Input[S <: Sys[S]] extends /* NuagesData[S] */ Disposable[S#Tx] {
    def attribute: NuagesAttribute[S]

    def value: Vec[Double]

    def numChannels: Int
  }
}
trait NuagesAttribute[S <: Sys[S]] extends /* NuagesData[S] */ NuagesParam[S] {
  // def parent: NuagesObj[S]

  def addPNode   (in: NuagesAttribute.Input[S], n: PNode, isFree: Boolean): Unit
  def removePNode(in: NuagesAttribute.Input[S], n: PNode                 ): Unit

  def spec: ParamSpec

  /** The value is normalized in the range 0 to 1 */
  def value: Vec[Double]

  def numChannels: Int

//  /** The value is normalized in the range 0 to 1 */
//  def value1_=(v: Double): Unit

  // def mapping: Option[NuagesAttribute.Mapping[S]]

  def removeMapping()(implicit tx: S#Tx): Unit

  /** Adjusts the control with the given normalized value. */
  def setControl(v: Vec[Double], instant: Boolean): Unit
}