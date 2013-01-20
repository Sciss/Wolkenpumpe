/*
 *  ProcFactoryView.scala
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
 *
 *
 *  Changelog:
 */

package de.sciss.nuages

import de.sciss.synth.proc.ProcFactory
import javax.swing.event.{ListSelectionEvent, ListSelectionListener}
import javax.swing.{DefaultListCellRenderer, ListSelectionModel, JList, JScrollPane, ScrollPaneConstants, AbstractListModel}
import java.awt.{Component, Color}

class ProcFactoryView
extends JScrollPane( ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                     ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER ) {
   private var sel = Option.empty[ ProcFactory ]
   private val ggList  = new JList( ListModel )
   ggList.setBackground( Color.black )
   ggList.setCellRenderer( ProcFactoryCellRenderer )
   ggList.setFixedCellWidth( 64 )
//   ggList.setVisibleRowCount( preferredNumRows )
   ggList.setSelectionMode( ListSelectionModel.SINGLE_SELECTION )
   ggList.addListSelectionListener( new ListSelectionListener {
      def valueChanged( e: ListSelectionEvent ) {
         if( e.getValueIsAdjusting ) return
         val pf0 = ggList.getSelectedValue
         sel = if( pf0 != null ) Some( pf0.asInstanceOf[ ProcFactory ]) else None
//         fun( pf )
      }
   })
   setViewportView( ggList )

   def add( pfs: ProcFactory* ) { ListModel.add( pfs: _* )}
   def remove( pfs: ProcFactory* ) { ListModel.remove( pfs: _* )}
   def selection : Option[ ProcFactory ] = sel

   def visibleRowCount : Int = ggList.getVisibleRowCount
   def visibleRowCount_=( n: Int ) { ggList.setVisibleRowCount( n )}

   def numRows : Int = ListModel.getSize

   private object ListModel extends AbstractListModel with Ordering[ ProcFactory ] {
      model =>

      private var coll = Vector.empty[ ProcFactory ]

      def remove( pfs: ProcFactory * ) {
         val indices = pfs.map( Util.binarySearch( coll, _ )( model )).filter( _ >= 0 )
         coll = coll.diff( pfs )
         val index0 = indices.min
         val index1 = indices.max
         removed( index0, index1 ) // WARNING: IllegalAccessError with fireIntervalRemoved
      }

      def add( pfs: ProcFactory* ) {
//println( "ADDING " + pfs )
         var index0 = Int.MaxValue
         var index1 = Int.MinValue
         pfs.foreach( pf => {
            val idx  = Util.binarySearch( coll, pf )( model )
            val idx0 = if( idx < 0) (-idx - 1) else idx
            coll     = coll.patch( idx0, Vector( pf ), 0 )
            // goddamnit
            if( idx0 <= index1 ) index1 += 1
            index0   = math.min( index0, idx0 )
            index1   = math.max( index1, idx0 )
         })
         // WARNING: IllegalAccessError with fireIntervalAdded
         if( index0 <= index1 ) added( index0, index1 )
      }

      private def removed( index0: Int, index1: Int ) {
         fireIntervalRemoved( model, index0, index1 )
      }

      private def added( index0: Int, index1: Int ) {
         fireIntervalAdded( model, index0, index1 )
      }

      // Ordering
      def compare( a: ProcFactory, b: ProcFactory ) = a.name.toUpperCase.compare( b.name.toUpperCase )

      // AbstractListModel
      def getSize : Int = coll.size
      def getElementAt( idx: Int ) : ProcFactory = coll( idx )
   }

   private object ProcFactoryCellRenderer extends DefaultListCellRenderer {
      private val colrUnfocused = new Color( 0xC0, 0xC0, 0xC0 )

      setFont( Wolkenpumpe.condensedFont.deriveFont( 15f )) // WARNING: use float argument

      override def getListCellRendererComponent( list: JList, value: AnyRef, index: Int,
         isSelected: Boolean, isFocused: Boolean ) : Component = {

         val str = value match {
            case pf: ProcFactory => pf.name
            case other => other.toString
         }
         setText( str )
         setBackground( if( isSelected ) { if( isFocused ) Color.white else colrUnfocused } else Color.black )
         setForeground( if( isSelected ) Color.black else Color.white )
         this
      }
   }
}