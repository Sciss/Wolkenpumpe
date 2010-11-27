package de.sciss.nuages

import de.sciss.synth.proc.impl.{GraphImpl, FactoryBuilderImpl}
import de.sciss.synth.GE
import de.sciss.synth.proc.{ProcGraph, ThreadLocalObject, ProcAnatomy, ProcDiff, ProcFilter, ProcGen, ProcFactoryBuilder, ProcDemiurg, DSL => PDSL, ProcTxn, ProcFactory}

/**
*    @version 0.11, 12-Jul-10
*/
object NuagesDSL {
   /**
    * Generates a sound generating process factory
    * with the given name and described through
    * the given code block.
    */
   def ngen( name: String )( thunk: => Unit )( implicit tx: ProcTxn ) : ProcFactory = {
      val res = NProcFactoryBuilder.gen( name )( thunk )
      ProcDemiurg.addFactory( res )
      res
   }

   /**
    * Generates a sound filtering (transformation) process factory
    * with the given name and described through
    * the given code block.
    */
   def nfilter( name: String )( thunk: => Unit )( implicit tx: ProcTxn ) : ProcFactory = {
      val res = NProcFactoryBuilder.filter( name )( thunk )
      ProcDemiurg.addFactory( res )
      res
   }

   /**
    * Generates a sound diffusing process factory
    * with the given name and described through
    * the given code block.
    */
   def ndiff( name: String )( thunk: => Unit )( implicit tx: ProcTxn ) : ProcFactory = {
      val res = NProcFactoryBuilder.diff( name )( thunk )
      ProcDemiurg.addFactory( res )
      res
   }
}

object NProcFactoryBuilder {
   def gen( name: String )( thunk: => Unit ) : ProcFactory =
      apply( NFactoryBuilderImpl.gen( name ), thunk )

   def filter( name: String )( thunk: => Unit ) : ProcFactory =
      apply( NFactoryBuilderImpl.filter( name ), thunk )

   def diff( name: String )( thunk: => Unit ) : ProcFactory =
      apply( NFactoryBuilderImpl.diff( name ), thunk )

   private def apply( b: ProcFactoryBuilder, thunk: => Unit ) : ProcFactory = ProcFactoryBuilder.use( b ) {
      thunk
      b.finish
   }
}

object NFactoryBuilderImpl {
   def gen( name: String ) : ProcFactoryBuilder =
      new NFactoryBuilderImpl( name, ProcGen, false, true )

   def filter( name: String ) : ProcFactoryBuilder =
      new NFactoryBuilderImpl( name, ProcFilter, true, true )

   def diff( name: String ) : ProcFactoryBuilder =
      new NFactoryBuilderImpl( name, ProcDiff, true, false )
}

class NFactoryBuilderImpl private( _name: String, _anatomy: ProcAnatomy, _impAudioIn: Boolean, _impAudioOut: Boolean )
extends FactoryBuilderImpl( _name, _anatomy, _impAudioIn, _impAudioOut ) {
//   def graphIn( fun: GE => GE ) : ProcGraph = {
//      val fullFun = () => fun( implicitInAr )
//      graph( fullFun )
//   }
//
//   def graphInOut( fun: GE => GE ) : ProcGraph = {
//      val fullFun = () => {
//         val in   = implicitInAr
//         val out  = fun( in )
//         implicitOutAr( out )
//      }
//      graph( fullFun )
//   }
//
//   def graphOut( fun: () => GE ) : ProcGraph = {
//      val fullFun = () => implicitOutAr( fun() )
//      graph( fullFun )
//   }
//
//   def graph( fun: () => GE ) : ProcGraph = {
//      requireOngoing
//      val res = new GraphImpl( fun )
//      enter( res )
//      res
//   }
//
//   override def graph( fun: () => GE ) : ProcGraph = {
//      val funky = () => {
//         val res: GE = fun.apply()
//println( "AQI " + res + " :: " + res.getClass )
//         res.poll( 1, name )
//         res
//      }
//      super.graph( funky )
//   }
}
