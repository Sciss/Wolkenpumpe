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