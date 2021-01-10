/*
 *  GlobalControl.scala
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

import java.awt.Point
import java.awt.event.{KeyEvent, MouseEvent, MouseMotionListener}

import de.sciss.lucre.synth.Txn

/** A control that remembers the last mouse location. */
final class GlobalControl[T <: Txn[T]](main: NuagesPanel[T]) extends MouseMotionListener {
  private val lastPt        = new Point

  main.display.addMouseMotionListener(this)

  def mouseDragged(e: MouseEvent): Unit = lastPt.setLocation(e.getX, e.getY)
  def mouseMoved  (e: MouseEvent): Unit = lastPt.setLocation(e.getX, e.getY)

  def keyPressed (e: KeyEvent): Unit = ()
  def keyReleased(e: KeyEvent): Unit = ()
}
