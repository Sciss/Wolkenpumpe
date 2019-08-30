/*
 *  VisualData.scala
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

import java.awt.event.MouseEvent
import java.awt.geom.Point2D
import java.awt.{Graphics2D, Shape}

import de.sciss.lucre.stm.{Disposable, Sys}
import prefuse.visual.VisualItem

/** The common trait of all visible objects on the
  * Prefuse display.
  *
  * The next sub-type is `VisualNode` that is represented by a graph node.
  *
  * @see [[NuagesNode]]
  */
trait NuagesData[S <: Sys[S]] extends Disposable[S#Tx] {
  // ---- methods to be called on the EDT ----

  /** GUI property: whether the node is allowed to move around
    * as part of the dynamic layout (`false`) or not (`true`).
    */
  var fixed: Boolean

  /** Called from drag-control: updates the
    * current geometric shape of the corresponding visual item.
    */
  def update(shp: Shape): Unit

  /** Asks the receiver to paint its GUI representation. */
  def render(g: Graphics2D, vi: VisualItem): Unit

  /** Called when the pointer device has entered the item. */
  def itemEntered (vi: VisualItem, e: MouseEvent, pt: Point2D): Unit

  /** Called when the pointer device has exited the item. */
  def itemExited  (vi: VisualItem, e: MouseEvent, pt: Point2D): Unit

  /** Called when the pointer device has pressed the item.
    *
    * @return `true` if the event was handled, `false` if it was ignored and
    *         should bubble up to the parent container
    */
  def itemPressed (vi: VisualItem, e: MouseEvent, pt: Point2D): Boolean

  /** Called when the pointer device has released the item. */
  def itemReleased(vi: VisualItem, e: MouseEvent, pt: Point2D): Unit

  /** Called when the pointer device has dragged the item. */
  def itemDragged (vi: VisualItem, e: MouseEvent, pt: Point2D): Unit

  /** Called when a key is pressed over the item.
    *
    * @return `true` if the event was handled, `false` if it was ignored and
    *         should bubble up to the parent container
    */
  def itemKeyPressed(vi: VisualItem, e: KeyControl.Pressed): Boolean

  /** Called when a key is released over the item. */
  def itemKeyReleased(vi: VisualItem, e: KeyControl.Pressed): Unit

  /** Called when a key has been typed over the item. */
  def itemKeyTyped(vi: VisualItem, e: KeyControl.Typed): Unit

  /** GUI property: name displayed. */
  def name: String

  def outline: Shape
}