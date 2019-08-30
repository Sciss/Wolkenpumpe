/*
 *  NuagesAttribute.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2019 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
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
  /** Creates a new attribute view for a given `parent` object and an `attribute` key.
    * This then set's the attribute view's input to an `Input` instance created from `value`.react
    */
  def apply[S <: SSys[S]](key: String, value: Obj[S], parent: NuagesObj[S])
                        (implicit tx: S#Tx, context: NuagesContext[S]): NuagesAttribute[S] =
    Impl(key = key, _value = value, parent = parent)

  def mkInput[S <: SSys[S]](attr: NuagesAttribute[S], parent: Parent[S], frameOffset: Long, value: Obj[S])
                           (implicit tx: S#Tx, context: NuagesContext[S]): Input[S] =
    Impl.mkInput(attr, parent, frameOffset = frameOffset, value = value)

  // ---- Factory ----

  /** Factory for creating instances of `Input`, i.e. for a specific type of object
    * plugged into an attribute map.
    */
  trait Factory {
    def typeId: Int

    type Repr[~ <: Sys[~]] <: Obj[~]

    /** @param  frameOffset accumulated absolute offset or `Long.MaxValue` if undefined.
      */
    def apply[S <: SSys[S]](attr: NuagesAttribute[S], parent: Parent[S], frameOffset: Long, value: Repr[S])
                           (implicit tx: S#Tx, context: NuagesContext[S]): Input[S]

    /** Tries to transition an old view to a new view.
      * If successful (`Some`), the old view will have been disposed of if necessary!
      */
    def tryConsume[S <: SSys[S]](oldInput: Input[S], newOffset: Long, newValue: Repr[S])
                                (implicit tx: S#Tx, context: NuagesContext[S]): Option[Input[S]]
  }

  def addFactory(f: Factory): Unit = Impl.addFactory(f)

  def factories: Iterable[Factory] = Impl.factories

  def getFactory[S <: Sys[S]](value: Obj[S]): Option[Factory] = Impl.getFactory(value)

  // ----

  trait Mapping[S <: Sys[S]] {
    /** The metering synth that via `SendTrig` updates the control's current value. */
    def synth: Ref[Option[Synth]]

    var source: Option[NuagesOutput[S]]
  }

  /** Representation of an input that is plugged into an attribute. */
  trait Input[S <: Sys[S]] extends Disposable[S#Tx] {
    /** The attribute's view. */
    def attribute: NuagesAttribute[S]

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

    /** The model object of this view. */
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

  /** A logical structure that specifies the parent container
    * of an attribute input. For example, if a scalar input is
    * directly plugged into an attribute, it's parent will be
    * an instance of `NuagesAttribute` itself. If the input is
    * active by being a child within a grapheme, the parent will
    * point to an instance of `NuagesGraphemeAttrInput`.
    */
  trait Parent[S <: Sys[S]] {
    /** Updates a child, possibly moving it into a grapheme if
      * the underlying nuages surface is a timeline.
      * If there are future events, they should be removed by this action.
      *
      * @param  before  reference to the currently active value
      * @param  now     new value to insert or replace
      * @param  dt      delay with respect to current position (zero for no delay)
      */
    def updateChild(before: Obj[S], now: Obj[S], dt: Long, clearRight: Boolean)(implicit tx: S#Tx): Unit

//    /** Updates with a given time offset `dt` in sample frames
//      * (may be negative)
//      *
//      * @param  before  a purely referential parameter used
//      *                 for copying param-spec to the `now` value
//      */
//    def updateChildDelay(before: Obj[S], now: Obj[S], dt: Long)(implicit tx: S#Tx): Unit

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
  def tryReplace(newValue: Obj[S])(implicit tx: S#Tx, context: NuagesContext[S]): Option[NuagesAttribute[S]]

  def numericValue: Vec[Double]
}