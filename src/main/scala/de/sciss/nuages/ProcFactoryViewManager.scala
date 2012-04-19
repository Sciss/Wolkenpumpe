/*
 *  ProcFactoryViewManager.scala
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

import de.sciss.synth.proc.{ProcFactory, ProcTxn, ProcDemiurg, ProcGen, ProcFilter, ProcDiff, ProcAnatomy}
import java.awt.EventQueue

//object ProcFactoryViewManager {
//   def apply( implicit txn: ProcTxn ) : ProcFactoryViewManager = {
//      val m = new ProcFactoryViewManager()
//      ProcDemiurg.addListener( m )
//   }
//}
class ProcFactoryViewManager( config: NuagesConfig ) extends ProcDemiurg.Listener {
   private val viewMap: Map[ ProcAnatomy, ProcFactoryView ] = Map(
      ProcGen     -> new ProcFactoryView,
      ProcFilter  -> new ProcFactoryView,
      ProcDiff    -> new ProcFactoryView
   )

   def view( ana: ProcAnatomy ) = viewMap( ana )

   def startListening( implicit txn: ProcTxn ) { ProcDemiurg.addListener( this )}
   def stopListening(  implicit txn: ProcTxn ) { ProcDemiurg.removeListener( this )}

   private def groupByAnatomy( set: Set[ ProcFactory ]) : Map[ ProcAnatomy, Set[ ProcFactory ]] = {
      val filtered   = set.filterNot( f => { val c = f.name.charAt( 0 ); c == '$' || c == '_' })
      val byAnatomy0 = filtered.groupBy( _.anatomy )
      if( config.collector ) {
         val (diff0, flt) = byAnatomy0.getOrElse( ProcFilter, Set.empty[ ProcFactory ]).partition( _.name.startsWith( "O-" ))
         val diff = diff0 ++ byAnatomy0.getOrElse( ProcDiff, Set.empty[ ProcFactory ])
         byAnatomy0 + (ProcDiff -> diff) + (ProcFilter -> flt)
      } else byAnatomy0
   }

   private def defer( thunk: => Unit ) {
      EventQueue.invokeLater( new Runnable { def run() { thunk }})
   }

   def updated( u: ProcDemiurg.Update ) { defer {
      if( u.factoriesRemoved.nonEmpty ) {
         groupByAnatomy( u.factoriesRemoved ) foreach { tup =>
            val (ana, facts) = tup
            viewMap.get( ana ).foreach( _.remove( facts.toSeq: _* ))
         }
      }
      if( u.factoriesAdded.nonEmpty ) {
         groupByAnatomy( u.factoriesAdded ) foreach { tup =>
            val (ana, facts) = tup
            viewMap.get( ana ).foreach( _.add( facts.toSeq: _* ))
         }
      }
   }}
}