/*
 *  PanelLayout.scala
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

package de.sciss.nuages.impl

import java.awt.{Dimension, Rectangle, LayoutManager}

class PanelLayout(peer: javax.swing.JComponent) extends LayoutManager {
  def layoutContainer(parent: java.awt.Container): Unit =
    peer.setBounds(new Rectangle(0, 0, parent.getWidth, parent.getHeight))

  def minimumLayoutSize  (parent: java.awt.Container): Dimension = peer.getMinimumSize
  def preferredLayoutSize(parent: java.awt.Container): Dimension = peer.getPreferredSize

  def removeLayoutComponent(comp: java.awt.Component) = ()

  def addLayoutComponent(name: String, comp: java.awt.Component) = ()
}
