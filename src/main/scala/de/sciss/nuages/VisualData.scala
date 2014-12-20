/*
 *  VisualData.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2014 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import java.awt.{Graphics2D, Shape}
import java.awt.event.MouseEvent
import de.sciss.lucre.synth.Sys
import prefuse.visual.VisualItem
import java.awt.geom.Point2D

trait VisualData[S <: Sys[S]] {
  var fixed: Boolean

  def update(shp: Shape): Unit

  def render(g: Graphics2D, vi: VisualItem): Unit

  def itemEntered (vi: VisualItem, e: MouseEvent, pt: Point2D): Unit
  def itemExited  (vi: VisualItem, e: MouseEvent, pt: Point2D): Unit
  def itemPressed (vi: VisualItem, e: MouseEvent, pt: Point2D): Boolean
  def itemReleased(vi: VisualItem, e: MouseEvent, pt: Point2D): Unit
  def itemDragged (vi: VisualItem, e: MouseEvent, pt: Point2D): Unit

  def name: String
}