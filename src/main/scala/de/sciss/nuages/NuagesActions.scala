/*
 *  NuagesActions.scala
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

import java.awt.Point
import java.awt.geom.Point2D
import prefuse.visual.NodeItem
import javax.swing.{JComponent, JPanel, Box, BorderFactory, BoxLayout}
import de.sciss.synth.proc.{ProcAudioInput, ProcAudioOutput, ProcAnatomy, DSL, ProcDiff, ProcGen, ProcFactory, ProcTxn, ProcFilter}

class NuagesActions( nuages: NuagesPanel ) {
//   private val font = Wolkenpumpe.condensedFont.deriveFont( 15f ) // WARNING: use float argument
   private var fltDialogDoneAction: ProcFactory => ProcTxn => Unit = f => t => ()

   private def createOverlayPanel = {
      val p = new OverlayPanel
      p.setLayout( new BoxLayout( p, BoxLayout.Y_AXIS ))
      p.setBorder( BorderFactory.createEmptyBorder( 12, 12, 12, 12 ))
      p
   }

   private def createFactoryView( ana: ProcAnatomy ) = {
      val fm = nuages.factoryManager
      val v = fm.view( ana )
      v.visibleRowCount = math.max( 4, math.min( 12, v.numRows ))
      v
   }

   private def vSpace( p: JComponent ) {
      p.add( Box.createVerticalStrut( 4 ))
   }

   private def hSpace( p: JComponent ) {
      p.add( Box.createHorizontalStrut( 8 ))
   }

   private def close( p: JPanel ) {
      p.getParent.remove( p )
   }

   private def pack( p: JPanel ) = {
      p.setSize( p.getPreferredSize )
      p.validate()
      p
   }

   private def createAbortBar( p: JPanel )( createAction: => Unit ) : Box = {
      val b = Box.createHorizontalBox()
      b.add( BasicButton( "Create" )( createAction ))
      hSpace( b )
      b.add( BasicButton( "Abort" )( close( p )))
      p.add( b )
      b
   }

   private lazy val createGenDialog = {
      val p = createOverlayPanel
      val genView = createFactoryView( ProcGen )
      p.add( genView )
      vSpace( p )
      val diffView = createFactoryView( ProcDiff )
      p.add( diffView )
      vSpace( p )
      createAbortBar( p ) {
         (genView.selection, diffView.selection) match {
            case (Some( genF ), Some( diffF )) =>
               close( p )
               val displayPt = nuages.display.getAbsoluteCoordinate( p.getLocation, null )
               ProcTxn.atomic { implicit txn => createProc( genF, diffF, displayPt )}
            case _ =>
         }
      }
      pack( p )
   }

   private lazy val createFilterDialog = {
      val p = createOverlayPanel
      val fltView = createFactoryView( ProcFilter )
      p.add( fltView )
      vSpace( p )
      createAbortBar( p )( fltView.selection.foreach { fltF =>
         close( p )
         ProcTxn.atomic( fltDialogDoneAction( fltF )( _ ))
      })
      pack( p )
   }

   def showCreateGenDialog( pt: Point ) : Boolean = {
      nuages.showOverlayPanel( createGenDialog, pt )
   }

   def showCreateFilterDialog( nSrc: NodeItem, nTgt: NodeItem, out: ProcAudioOutput, in: ProcAudioInput, pt: Point ) : Boolean = {
      if( nuages.isOverlayShowing ) return false
      fltDialogDoneAction = { fltF =>
         nSrc.setFixed( false ) // XXX woops.... we have to clean up the mess of ConnectControl
         nTgt.setFixed( false )
         val displayPt = nuages.display.getAbsoluteCoordinate( pt, null )
         createFilter( out, in, fltF, displayPt )( _ )
      }
      nuages.showOverlayPanel( createFilterDialog, pt )
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

   def createFilter( out: ProcAudioOutput, in: ProcAudioInput, fltF: ProcFactory, pt: Point2D )( implicit txn: ProcTxn ) {
      import DSL._
//println( "filter from out " + out.bus + " to " + in.bus )
      val filter  = fltF.make
      filter.bypass
      out ~|filter|> in
      if( !filter.isPlaying ) filter.play
      txn.beforeCommit { _ =>
         nuages.setLocationHint( filter, pt )
      }
   }
}