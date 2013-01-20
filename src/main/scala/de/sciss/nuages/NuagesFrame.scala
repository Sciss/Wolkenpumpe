/*
 *  NuagesFrame.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2013 Hanns Holger Rutz. All rights reserved.
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
import de.sciss.synth.proc._
import plaf.basic.BasicSliderUI
import javax.swing.event.{ChangeListener, ChangeEvent}
import java.awt.{Toolkit, GridBagConstraints, GridBagLayout, Color, BorderLayout}
import java.awt.event.{KeyEvent, ActionEvent, InputEvent}

class NuagesFrame( val config: NuagesConfig )
extends JFrame( "Wolkenpumpe") /* with ProcDemiurg.Listener */ {
   frame =>

   val panel      = new NuagesPanel( config )
   val transition = new NuagesTransitionPanel( panel );
   val bottom     = {
      val p = new BasicPanel
      p.setLayout( new BoxLayout( p, BoxLayout.X_AXIS ))
      p
   }

   // ---- constructor ----
   {
      val cp = getContentPane
      cp.setBackground( Color.black )
//      val ggEastBox     = new BasicPanel
//      ggEastBox.setLayout( new BorderLayout() )
//      val font          = Wolkenpumpe.condensedFont.deriveFont( 15f ) // WARNING: use float argument

      val ggSouthBox = Box.createHorizontalBox()
      ggSouthBox.add( bottom )
      ggSouthBox.add( Box.createHorizontalGlue() )
      ggSouthBox.add( transition )

//      ggEastBox.add( transition, BorderLayout.SOUTH )
//      cp.add( BorderLayout.EAST, ggEastBox )
      cp.add( panel, BorderLayout.CENTER )

      setDefaultCloseOperation( WindowConstants.DISPOSE_ON_CLOSE )

      val gridLay       = new GridBagLayout() 
      val ggFaderBox    = new JPanel( gridLay )
//      ggEastBox.add( ggFaderBox, BorderLayout.EAST )
      cp.add( ggSouthBox, BorderLayout.SOUTH )
      cp.add( ggFaderBox, BorderLayout.EAST )

      val gridCon       = new GridBagConstraints()
      gridCon.fill      = GridBagConstraints.BOTH
      gridCon.weightx   = 1.0
      gridCon.gridwidth = GridBagConstraints.REMAINDER

      def mkFader( ctrlSpecT: (ParamSpec, Double), weighty: Double )( fun: (Double, ProcTxn) => Unit ) {
         val (ctrlSpec, ctrlInit) = ctrlSpecT
         val slidSpec   = ParamSpec( 0, 0x10000 )
         val slidInit   = slidSpec.map( ctrlSpec.unmap( ctrlInit )).toInt
         val slid       = new JSlider( SwingConstants.VERTICAL, slidSpec.lo.toInt, slidSpec.hi.toInt, slidInit )
         slid.setUI( new BasicSliderUI( slid ))
         slid.setBackground( Color.black )
         slid.setForeground( Color.white )
         slid.addChangeListener( new ChangeListener {
            def stateChanged( e: ChangeEvent ) {
               val ctrlVal = ctrlSpec.map( slidSpec.unmap( slid.getValue ))
//               grpMaster.set( ctrlName -> ctrlVal )
               ProcTxn.atomic { t => fun( ctrlVal, t )}
            }
         })
         gridCon.weighty = weighty
         gridLay.setConstraints( slid, gridCon )
         ggFaderBox.add( slid )
      }

      if( config.masterChannels.isDefined ) mkFader( NuagesPanel.masterAmpSpec, 0.75 )( panel.setMasterVolume( _ )( _ ))
      if( config.soloChannels.isDefined )   mkFader( NuagesPanel.soloAmpSpec, 0.25 )( panel.setSoloVolume( _ )( _ ))
      
//      ProcTxn.atomic { implicit t =>
//         ProcDemiurg.addListener( frame )
//      }
      if( config.fullScreenKey ) installFullScreenKey()
//      panel.display.requestFocus
   }

   private def installFullScreenKey() {
      val d       = panel.display
      val imap    = d.getInputMap( JComponent.WHEN_IN_FOCUSED_WINDOW )
      val amap    = d.getActionMap
      val fsName  = "fullscreen"
      imap.put( KeyStroke.getKeyStroke( KeyEvent.VK_F, Toolkit.getDefaultToolkit.getMenuShortcutKeyMask |
         InputEvent.SHIFT_MASK ), fsName )
      amap.put( fsName, new AbstractAction( fsName ) {
         def actionPerformed( e: ActionEvent ) {
            val sd = getGraphicsConfiguration.getDevice
            sd.setFullScreenWindow( if( sd.getFullScreenWindow == frame ) null else frame )
         }
      })
   }

   override def dispose() {
//      ProcTxn.atomic { implicit t => ProcDemiurg.removeListener( frame )}
      panel.dispose()
      super.dispose()
   }

//   private def defer( thunk: => Unit ) {
//      EventQueue.invokeLater( new Runnable { def run() = thunk })
//   }
}