/*
 *  NuagesAttribute.scala
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

import de.sciss.lucre.synth.Synth
import de.sciss.lucre.{Disposable, Obj, Txn, synth}
import de.sciss.nuages.impl.{NuagesAttributeImpl => Impl}
import de.sciss.proc.ParamSpec
import prefuse.data.{Node => PNode}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.Ref

object NuagesAttribute {
  /** Creates a new attribute view for a given `parent` object and an `attribute` key.
    * This then set's the attribute view's input to an `Input` instance created from `value`.react
    */
  def apply[T <: synth.Txn[T]](key: String, value: Obj[T], parent: NuagesObj[T])
                        (implicit tx: T, context: NuagesContext[T]): NuagesAttribute[T] =
    Impl(key = key, _value = value, parent = parent)

  def mkInput[T <: synth.Txn[T]](attr: NuagesAttribute[T], parent: Parent[T], frameOffset: Long, value: Obj[T])
                           (implicit tx: T, context: NuagesContext[T]): Input[T] =
    Impl.mkInput(attr, parent, frameOffset = frameOffset, value = value)

  // ---- Factory ----

  /** Factory for creating instances of `Input`, i.e. for a specific type of object
    * plugged into an attribute map.
    */
  trait Factory {
    def typeId: Int

    type Repr[~ <: Txn[~]] <: Obj[~]

    /** @param  frameOffset accumulated absolute offset or `Long.MaxValue` if undefined.
      */
    def apply[T <: synth.Txn[T]](attr: NuagesAttribute[T], parent: Parent[T], frameOffset: Long, value: Repr[T])
                           (implicit tx: T, context: NuagesContext[T]): Input[T]

    /** Tries to transition an old view to a new view.
      * If successful (`Some`), the old view will have been disposed of if necessary!
      */
    def tryConsume[T <: synth.Txn[T]](oldInput: Input[T], newOffset: Long, newValue: Repr[T])
                                (implicit tx: T, context: NuagesContext[T]): Option[Input[T]]
  }

  def addFactory(f: Factory): Unit = Impl.addFactory(f)

  def factories: Iterable[Factory] = Impl.factories

  def getFactory[T <: Txn[T]](value: Obj[T]): Option[Factory] = Impl.getFactory(value)

  // ----

  trait Mapping[T <: Txn[T]] {
    /** The metering synth that via `SendTrig` updates the control's current value. */
    def synth: Ref[Option[Synth]]

    var source: Option[NuagesOutput[T]]
  }

  /** Representation of an input that is plugged into an attribute. */
  trait Input[T <: Txn[T]] extends Disposable[T] {
    /** The attribute's view. */
    def attribute: NuagesAttribute[T]

    // ---- transactional ----

    /** Tries to migrate the passed object to this input view.
      * That is, if the view can exchange its model for this
      * new object, it should do so and return `true`.
      * Returning `false` means the object cannot be consumed,
      * for example because it is of a different type.
      */
    def tryConsume(newOffset: Long, newValue: Obj[T])(implicit tx: T): Boolean

    def inputParent(implicit tx: T): Parent[T]
    def inputParent_=(p: Parent[T])(implicit tx: T): Unit

    /** The model object of this view. */
    def input(implicit tx: T): Obj[T]

    def numChildren(implicit tx: T): Int

    /** Runs a deep collection for particular input. This
      * will perform a nested search for collection views
      * such as grapheme or timeline.
      *
      * @param  pf  the matcher function to apply to the leaves of the traversal
      * @return an iterator over all elements that were successfully matched
      */
    def collect[A](pf: PartialFunction[Input[T], A])(implicit tx: T): Iterator[A]
  }

  /** An attribute or attribute input that provides
    * a numeric view of its current state.
    */
  trait Numeric {
    /** On the EDT! */
    def numericValue: Vec[Double]
  }

  /** A logical structure that specifies the parent container
    * of an attribute input. For example, if a scalar input is
    * directly plugged into an attribute, it's parent will be
    * an instance of `NuagesAttribute` itself. If the input is
    * active by being a child within a grapheme, the parent will
    * point to an instance of `NuagesGraphemeAttrInput`.
    */
  trait Parent[T <: Txn[T]] {
    /** Updates a child, possibly moving it into a grapheme if
      * the underlying nuages surface is a timeline.
      * If there are future events, they should be removed by this action.
      *
      * @param  before  reference to the currently active value
      * @param  now     new value to insert or replace
      * @param  dt      delay with respect to current position (zero for no delay)
      */
    def updateChild(before: Obj[T], now: Obj[T], dt: Long, clearRight: Boolean)(implicit tx: T): Unit

//    /** Updates with a given time offset `dt` in sample frames
//      * (may be negative)
//      *
//      * @param  before  a purely referential parameter used
//      *                 for copying param-spec to the `now` value
//      */
//    def updateChildDelay(before: Obj[T], now: Obj[T], dt: Long)(implicit tx: T): Unit

    /** Removes a child, possibly moving it into a timeline if
      * the underlying nuages surface is a timeline.
      */
    def removeChild(child: Obj[T])(implicit tx: T): Unit

    /** Adds a child, possibly moving it into a timeline if
      * the underlying nuages surface is a timeline.
      */
    def addChild(child: Obj[T])(implicit tx: T): Unit

    def numChildren(implicit tx: T): Int
  }
}

trait NuagesAttribute[T <: Txn[T]]
  extends NuagesAttribute.Input[T]
    with NuagesAttribute.Parent[T]
    with NuagesParam[T] {

  /** Connects a node with the attribute view.
    *
    * @param n        the node; the view will add an edge from this node to itself
    *                 (either its center, or if there is a 'summary' node, to that node)
    * @param isFree   if `true`, the node has "free movement", i.e. should be integrated
    *                 in the overall aggregate view of this attribute; if `false`, the
    *                 node is part of another structure, e.g. corresponds with the output
    *                 of another proc, and thus should not be added to the attribute's aggregate.
    */
  def addPNode   (n: PNode, isFree: Boolean): Unit
  def removePNode(n: PNode                 ): Unit

  def spec: ParamSpec

  def isControl: Boolean

  /** Attempts to replace the contents of the view.
    *
    * @param newValue   the new value to attempt to associate with the view
    * @return `Some` if either the old view accepted the new value or if
    *         a new view was created that could "consume" the old view. This may
    *         happen for example if the new value is a container with a single
    *         element and the old view can replace its own single element.
    *         `None` if this was not possible and the caller should act
    *         accordingly (dispose the old view, create a fresh new view).
    */
  def tryReplace(newValue: Obj[T])(implicit tx: T, context: NuagesContext[T]): Option[NuagesAttribute[T]]

  /** The current shown value on the UI, when patched to an input, or `null` if unknown.
    * In order to obtain the numeric value in all cases, use `inputView` and see if it is
    * `NuagesAttribute.Numeric`.
    */
  def numericValue: Vec[Double]

  def inputView: NuagesAttribute.Input[T]
}