/*
 *  BasicButton.scala
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

import javax.swing.JButton
import javax.swing.plaf.basic.BasicButtonUI
import java.awt.event.{ActionListener, ActionEvent}

object BasicButton {
   def apply( label: String )( action: => Unit ) : BasicButton = new BasicButton( label, action )
}
class BasicButton private ( label: String, action: => Unit ) extends JButton( label ) {
   setUI( new BasicButtonUI() )
   setFocusable( false )
   setFont( Wolkenpumpe.condensedFont.deriveFont( 15f ))

   addActionListener( new ActionListener {
      def actionPerformed( e: ActionEvent ) {
         action
      }
   })
}