/*
 *  NuagesTransitionPanel.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2012 Hanns Holger Rutz. All rights reserved.
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

import javax.swing._
import event.{ChangeEvent, ChangeListener}
import java.awt.Color
import plaf.basic.{BasicSliderUI, BasicPanelUI}
import de.sciss.synth.proc._
import collection.JavaConversions

class NuagesTransitionPanel( main: NuagesPanel ) extends JPanel {
   panel =>

   private val bg                            = new ButtonGroup()
   private var models : Array[ ButtonModel ] = _
   private val specSlider                    = ParamSpec( 0, 0x10000 )
   private val specTime                      = ParamSpec( 0.06, 60.0, ExpWarp )
   private val ggSlider                      = new JSlider( specSlider.lo.toInt, specSlider.hi.toInt )

   // ---- constructor ----
   {
//      val font       = Wolkenpumpe.condensedFont.deriveFont( 15f ) // WARNING: use float argument

      def addButton( b: AbstractButton ) {
         bg.add( b )
//         box.add( b )
         panel.add( b )
      }

      def dispatchTransition( inst: Boolean, gl: Boolean, xf: Boolean ) {
         main.transition = if( inst ) {
            (_) => Instant
         } else {
            val fdt = specTime.map( specSlider.unmap( ggSlider.getValue() ))
            if( xf ) {
               XFade( _, fdt )
            } else {
               Glide( _, fdt )
            }
         }
      }


      setUI( new BasicPanelUI() )
      setBackground( Color.black )
      val ggTypeInstant = BasicToggleButton( "In" )( if( _ ) dispatchTransition( true, false, false ))
      val ggTypeGlide   = BasicToggleButton( "Gl" )( if( _ ) dispatchTransition( false, true, false ))
      val ggTypeXFade   = BasicToggleButton( "X" )(  if( _ ) dispatchTransition( false, false, true ))
      addButton( ggTypeInstant )
      addButton( ggTypeGlide )
      addButton( ggTypeXFade )
      models   = new JavaConversions.JEnumerationWrapper( bg.getElements ).toArray.map( _.getModel )
//      panel.setLayout( new BoxLayout( panel, BoxLayout.Y_AXIS ))
      panel.setLayout( new BoxLayout( panel, BoxLayout.X_AXIS ))
//      panel.add( box )
      ggSlider.setUI( new BasicSliderUI( ggSlider ))
      ggSlider.setBackground( Color.black )
      ggSlider.setForeground( Color.white )
      panel.add( ggSlider )

      ggSlider.addChangeListener( new ChangeListener {
         def stateChanged( e: ChangeEvent ) {
            if( !ggTypeInstant.isSelected ) {
               dispatchTransition( ggTypeInstant.isSelected, ggTypeGlide.isSelected, ggTypeXFade.isSelected )
            }
         }
      })
//      val actionListener = new ActionListener {
//         def actionPerformed( e: ActionEvent ) {
//            dispatchTransition()
//         }
//      }
//      ggTypeInstant.addActionListener( actionListener )
//      ggTypeGlide.addActionListener( actionListener )
//      ggTypeXFade.addActionListener( actionListener )
   }

   def setTransition( idx: Int, tNorm: Double ) {
      if( idx < 0 || idx > 2 ) return
      bg.setSelected( models( idx ), true )
      if( idx == 0 ) {
         main.transition = (_) => Instant
      } else {
         val tNormC  = math.max( 0.0, math.min( 1.0, tNorm ))
         val fdt     = specTime.map( tNormC ) 
         ggSlider.setValue( specSlider.map( tNormC ).toInt )
         main.transition = if( idx == 1 ) Glide( _, fdt ) else XFade( _, fdt )
      }
   }
}