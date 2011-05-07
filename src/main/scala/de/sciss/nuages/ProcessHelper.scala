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