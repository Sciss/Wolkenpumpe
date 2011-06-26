/*
 *  NuagesActions.scala
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
 *
 *
 *  Changelog:
 */

package de.sciss.nuages

import java.awt.Point
import java.awt.geom.Point2D
import javax.swing.{JButton, Box, BorderFactory, BoxLayout}
import javax.swing.plaf.basic.BasicButtonUI
import java.awt.event.{ActionEvent, ActionListener}
import de.sciss.synth.proc.{DSL, ProcDiff, ProcGen, ProcFactory, ProcTxn, ProcFilter}

class NuagesActions( nuages: NuagesPanel ) {
   private lazy val createGenDialog = {
      val p = new OverlayPanel
      p.setLayout( new BoxLayout( p, BoxLayout.Y_AXIS ))
      p.setBorder( BorderFactory.createEmptyBorder( 12, 12, 12, 12 ))
      val fm = nuages.factoryManager
      val genView = fm.view( ProcGen )
      genView.visibleRowCount = math.max( 4, math.min( 12, genView.numRows ))
      p.add( genView )
      p.add( Box.createVerticalStrut( 4 ))
      val diffView = fm.view( ProcDiff )
      diffView.visibleRowCount = math.max( 4, math.min( 12, diffView.numRows ))
      p.add( diffView )
      p.add( Box.createVerticalStrut( 4 ))
      val b = Box.createHorizontalBox()
      val font = Wolkenpumpe.condensedFont.deriveFont( 15f ) // WARNING: use float argument
      val ggOk = new JButton( "Create" )
      ggOk.setUI( new BasicButtonUI() )
      ggOk.setFocusable( false )
      ggOk.setFont( font )
      b.add( ggOk )
      b.add( Box.createHorizontalStrut( 8 ))
      val ggCancel = new JButton( "Abort" )
      ggCancel.setUI( new BasicButtonUI() )
      ggCancel.setFocusable( false )
      ggCancel.setFont( font )
      b.add( ggCancel )
      p.add( b )

      def close() { p.getParent.remove( p )}

      ggOk.addActionListener( new ActionListener {
         def actionPerformed( e: ActionEvent ) {
            (genView.selection, diffView.selection) match {
               case (Some( genF ), Some( diffF )) =>
                  close()
                  val displayPt = nuages.display.getAbsoluteCoordinate( p.getLocation, null )
                  ProcTxn.atomic { implicit txn => createProc( genF, diffF, displayPt )}
               case _ =>
            }
         }
      })
      ggCancel.addActionListener( new ActionListener {
         def actionPerformed( e: ActionEvent ) {
            close()
         }
      })

      p.setSize( p.getPreferredSize )
      p.validate()
      p
   }

   def showCreateGenDialog( pt: Point ) : Boolean = {
      nuages.showOverlayPanel( createGenDialog, pt )
   }

   def createProc( genF: ProcFactory, diffF: ProcFactory, pt: Point2D )( implicit txn: ProcTxn ) {
      import DSL._
      val gen  = genF.make
      val diff = diffF.make
      gen ~> diff
      if( diff.anatomy == ProcFilter ) { // this is the case for config.collector == true
         nuages.collector.foreach( diff ~> _ )
      }

      txn.beforeCommit { _ =>
         val genPt  = new Point2D.Double( pt.getX, pt.getY - 30 )
         val diffPt = new Point2D.Double( pt.getX, pt.getY + 30 )
         nuages.setLocationHint( gen, genPt )
         nuages.setLocationHint( diff, diffPt )
      }
   }
}