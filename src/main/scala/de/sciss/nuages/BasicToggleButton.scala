/*
 *  BasicToggleButton.scala
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

import javax.swing.JToggleButton
import java.awt.Color
import java.awt.event.{ActionListener, ActionEvent}

object BasicToggleButton {
   def apply( label: String )( action: Boolean => Unit ) =
      new BasicToggleButton( label, action )
}
class BasicToggleButton private ( label: String, action: Boolean => Unit ) extends JToggleButton( label ) {
   setUI( new SimpleToggleButtonUI )
   setFont( Wolkenpumpe.condensedFont.deriveFont( 15f ))
   setBackground( Color.black )
   setForeground( Color.white )

   addActionListener( new ActionListener {
      def actionPerformed( e: ActionEvent ) {
         action( isSelected )
      }
   })
}