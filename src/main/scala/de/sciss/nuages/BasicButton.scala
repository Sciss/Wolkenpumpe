package de.sciss.nuages

import javax.swing.JButton
import java.awt.event.ActionEvent
import javax.swing.plaf.basic.BasicButtonUI

class BasicButton( label: String )( action: => Unit ) extends JButton( label ) {
   setUI( new BasicButtonUI() )
   setFocusable( false )
   setFont( Wolkenpumpe.condensedFont.deriveFont( 15f ))

   def actionPerformed( e: ActionEvent ) {
      action
   }
}