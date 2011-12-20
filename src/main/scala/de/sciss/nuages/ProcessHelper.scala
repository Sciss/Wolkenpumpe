/*
 *  ProcessHelper.scala
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
 */

package de.sciss.nuages

import de.sciss.synth.proc.{ProcTxn, Proc}

object ProcessHelper {
   def whenGlideDone( p: Proc, ctrlName: String )( fun: ProcTxn => Unit )( implicit tx: ProcTxn ) {
      if( p.control( ctrlName ).cv.mapping.isEmpty ) {
         fun( tx )
      } else {
         lazy val l: Proc.Listener = new Proc.Listener {
            def updated( u: Proc.Update ) {
               if( u.controls.find( tup => (tup._1.name == ctrlName) && tup._2.mapping.isEmpty ).isDefined ) {
                  ProcTxn.atomic { implicit tx =>
                     p.removeListener( l )
                     fun( tx )
                  }
               }
            }
         }
         p.addListener( l )
      }
   }

   def whenFadeDone( p: Proc )( fun: ProcTxn => Unit )( implicit tx: ProcTxn ) {
      if( !p.state.fading ) {
         fun( tx )
      } else {
         lazy val l: Proc.Listener = new Proc.Listener {
            def updated( u: Proc.Update ) {
               if( !u.state.fading ) {
                  ProcTxn.atomic { implicit tx =>
                     p.removeListener( l )
                     fun( tx )
                  }
               }
            }
         }
         p.addListener( l )
      }
   }
}