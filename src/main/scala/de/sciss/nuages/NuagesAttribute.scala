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

import de.sciss.lucre.stm.{Sys, Obj}
import de.sciss.lucre.synth.{Synth, Sys => SSys}
import de.sciss.nuages.impl.{NuagesAttributeImpl => Impl}
import prefuse.data.{Node => PNode}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref
import scala.language.higherKinds

object NuagesAttribute {
  def apply[S <: SSys[S]](key: String, value: Obj[S], parent: NuagesObj[S], np: NodeProvider[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute[S] =
    Impl(key, value, parent, np)

  def tryApply[S <: SSys[S]](key: String, value: Obj[S], parent: NuagesObj[S], np: NodeProvider[S])
                           (implicit tx: S#Tx, context: NuagesContext[S]): Option[NuagesAttribute[S]] =
    Impl.tryApply(key, value, parent, np)

    // ---- Factory ----

  trait Factory {
    def typeID: Int

    type Repr[~ <: Sys[~]] <: Obj[~]

    def apply[S <: SSys[S]](key: String, value: Repr[S], parent: NuagesObj[S], np: NodeProvider[S])
                          (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute[S]
  }

  def addFactory(f: Factory): Unit = Impl.addFactory(f)

  def factories: Iterable[Factory] = Impl.factories

  // ----

//  def scalar[S <: Sys[S]](parent: NuagesObj[S], key: String,
//                          dObj: DoubleObj[S])(implicit tx: S#Tx): NuagesAttribute[S] =
//    impl.NuagesAttributeImpl.scalar(parent, key, dObj)
//
//  def vector[S <: Sys[S]](parent: NuagesObj[S], key: String,
//                          dObj: DoubleVector[S])(implicit tx: S#Tx): NuagesAttribute[S] =
//    impl.NuagesAttributeImpl.vector(parent, key, dObj)

  // SCAN
//  def scan[S <: Sys[S]](parent: VisualObj[S], key: String,
//                        sObj: Scan[S])(implicit tx: S#Tx): VisualControl[S] =
//    impl.VisualControlImpl.scan(parent, key, sObj)

  trait Mapping[S <: Sys[S]] {
    /** The metering synth that via `SendTrig` updates the control's current value. */
    def synth: Ref[Option[Synth]]

    var source: Option[NuagesOutput[S]]

    // SCAN
    // def scan(implicit tx: S#Tx): Scan[S]
  }

  trait NodeProvider[S <: Sys[S]] {
    def acquirePNode(a: NuagesAttribute[S]): PNode
    def releasePNode(a: NuagesAttribute[S]): Unit
  }
}
trait NuagesAttribute[S <: Sys[S]] extends NuagesParam[S] {

  def spec: ParamSpec

  /** The value is normalized in the range 0 to 1 */
  var value: Vec[Double]

  def numChannels: Int

//  /** The value is normalized in the range 0 to 1 */
//  def value1_=(v: Double): Unit

  def mapping: Option[NuagesAttribute.Mapping[S]]

  def removeMapping()(implicit tx: S#Tx): Unit

  /** Adjusts the control with the given normalized value. */
  def setControl(v: Vec[Double], instant: Boolean): Unit
}