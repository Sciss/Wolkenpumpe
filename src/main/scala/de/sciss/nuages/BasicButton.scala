package de.sciss.nuages

import javax.swing.JButton
import javax.swing.plaf.basic.BasicButtonUI
import java.awt.event.{ActionListener, ActionEvent}

class BasicButton( label: String )( action: => Unit ) extends JButton( label ) {
   setUI( new BasicButtonUI() )
   setFocusable( false )
   setFont( Wolkenpumpe.condensedFont.deriveFont( 15f ))

   addActionListener( new ActionListener {
      def actionPerformed( e: ActionEvent ) {
         action
      }
   })
}