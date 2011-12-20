/*
 *  NuagesProcRenderer.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2011 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either
 *  version 2, june 1991 of the License, or (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public
 *  License (gpl.txt) along with this software; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import prefuse.visual.VisualItem
import java.awt._
import geom._
import prefuse.render.AbstractShapeRenderer

class NuagesProcRenderer( size: Int )
extends AbstractShapeRenderer {
   import NuagesPanel._

   private val ellipse  = new Ellipse2D.Float()

   protected def getRawShape( vi: VisualItem ) : Shape = {
      var x    = vi.getX
      if( x.isNaN || x.isInfinity ) x = 0.0
      var y    = vi.getY
      if( y.isNaN || y.isInfinity ) y = 0.0
      val diam = size * vi.getSize
      if ( diam > 1 ) {
          x -= diam / 2
          y -= diam / 2
      }
      ellipse.setFrame( x, y, diam, diam )
      ellipse
   }

   override def render( g: Graphics2D, vi: VisualItem ) {
      val data = vi.get( COL_NUAGES ).asInstanceOf[ VisualData ]
      if( data == null ) return
      data.update( getShape( vi ))
      data.render( g, vi )
   }
}