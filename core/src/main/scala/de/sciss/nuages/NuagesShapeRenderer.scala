/*
 *  NuagesProcRenderer.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2020 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.lucre.synth.Txn
import prefuse.visual.VisualItem
import java.awt._
import geom._
import prefuse.render.AbstractShapeRenderer

class NuagesShapeRenderer[T <: Txn[T]](size: Int)
  extends AbstractShapeRenderer {

  import NuagesPanel._

  private val ellipse = new Ellipse2D.Float()

  protected def getRawShape(vi: VisualItem): Shape = {
    var x = vi.getX
    if (x.isNaN || x.isInfinity) x = 0.0
    var y = vi.getY
    if (y.isNaN || y.isInfinity) y = 0.0
    val diam = size * vi.getSize
    if (diam > 1) {
      x -= diam / 2
      y -= diam / 2
    }
    ellipse.setFrame(x, y, diam, diam)
    ellipse
  }

  override def render(g: Graphics2D, vi: VisualItem): Unit = {
    val data = vi.get(COL_NUAGES).asInstanceOf[NuagesData[T]]
    if (data == null) return
    data.update(getShape(vi))
    data.render(g, vi)
  }
}