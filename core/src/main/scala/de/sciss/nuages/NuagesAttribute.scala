/*
 *  NuagesAttribute.scala
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

import de.sciss.lucre.stm.{Disposable, Obj, Sys}
import de.sciss.lucre.synth.{Synth, Sys => SSys}
import de.sciss.nuages.impl.{NuagesAttributeImpl => Impl}
import prefuse.data.{Node => PNode}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref
import scala.language.higherKinds

object NuagesAttribute {
  def apply[S <: SSys[S]](key: String, value: Obj[S], parent: NuagesObj[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute[S] =
    Impl(key = key, _value = value, parent = parent)

  def mkInput[S <: SSys[S]](attr: NuagesAttribute[S], parent: Parent[S], frameOffset: Long, value: Obj[S])
                           (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] =
    Impl.mkInput(attr, parent, frameOffset = frameOffset, value = value)

  // ---- Factory ----

  trait Factory {
    def typeID: Int

    type Repr[~ <: Sys[~]] <: Obj[~]

    /** @param  frameOffset accumulated absolute offset or `Long.MaxValue` if undefined.
      */
    def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: Parent[S], frameOffset: Long, value: Repr[S])
                           (implicit tx: S#Tx, context: NuagesContext[S]): Input[S]

    def tryConsume[S <: SSys[S]](oldInput: Input[S], /* newOffset: Long, */ newValue: Repr[S])
                                (implicit tx: S#Tx, context: NuagesContext[S]): Option[Input[S]]
  }

  def addFactory(f: Factory): Unit = Impl.addFactory(f)

  def factories: Iterable[Factory] = Impl.factories

  // ----

  trait Mapping[S <: Sys[S]] {
    /** The metering synth that via `SendTrig` updates the control's current value. */
    def synth: Ref[Option[Synth]]

    var source: Option[NuagesOutput[S]]
  }

  trait Input[S <: Sys[S]] extends Disposable[S#Tx] {
    def attribute   : NuagesAttribute[S]

    // ---- edt ----

//    def value: Vec[Double]
//
//    def numChannels: Int

    // ---- transactional ----

    /** Tries to migrate the passed object to this input view.
      * That is, if the view can exchange its model for this
      * new object, it should do so and return `true`.
      * Returning `false` means the object cannot be consumed,
      * for example because it is of a different type.
      */
    def tryConsume(newOffset: Long, newValue: Obj[S])(implicit tx: S#Tx): Boolean

    def inputParent(implicit tx: S#Tx): Parent[S]
    def inputParent_=(p: Parent[S])(implicit tx: S#Tx): Unit

    def input(implicit tx: S#Tx): Obj[S]

    def numChildren(implicit tx: S#Tx): Int

    /** Runs a deep collection for particular input. This
      * will perform a nested search for collection views
      * such as grapheme or timeline.
      *
      * @param  pf  the matcher function to apply to the leaves of the traversal
      * @return an iterator over all elements that were successfully matched
      */
    def collect[A](pf: PartialFunction[Input[S], A])(implicit tx: S#Tx): Iterator[A]
  }

  /** An attribute or attribute input that provides
    * a numeric view of its current state.
    */
  trait Numeric {
    /** On the EDT! */
    def numericValue: Vec[Double]
  }

  trait Parent[S <: Sys[S]] {
    /** Updates a child, possibly moving it into a grapheme if
      * the underlying nuages surface is a timeline.
      */
    def updateChild(before: Obj[S], now: Obj[S])(implicit tx: S#Tx): Unit

    /** Removes a child, possibly moving it into a timeline if
      * the underlying nuages surface is a timeline.
      */
    def removeChild(child: Obj[S])(implicit tx: S#Tx): Unit

    /** Adds a child, possibly moving it into a timeline if
      * the underlying nuages surface is a timeline.
      */
    def addChild(child: Obj[S])(implicit tx: S#Tx): Unit

    def numChildren(implicit tx: S#Tx): Int
  }
}

trait NuagesAttribute[S <: Sys[S]]
  extends NuagesAttribute.Input[S]
    with NuagesAttribute.Parent[S]
    with NuagesParam[S] {

  def addPNode   (in: NuagesAttribute.Input[S], n: PNode, isFree: Boolean): Unit
  def removePNode(in: NuagesAttribute.Input[S], n: PNode                 ): Unit

  def spec: ParamSpec

  def isControl: Boolean

  /** Attempts to replace the contents of the view.
    *
    * @param newValue   the new value to attempt to associate with the view
    * @return `Some` if the either the old view accepted the new value or if
    *         a new view was created that could "consume" the old view. This may
    *         happen for example if the new value is a container with a single
    *         element and the old view can replace its own single element.
    *         `None` if this was not possible and the caller should act
    *         accordingly (dispose the old view, create a fresh new view).
    */
  def tryReplace(newValue: Obj[S])(implicit tx: S#Tx, context: NuagesContext[S]): Option[NuagesAttribute[S]]
}