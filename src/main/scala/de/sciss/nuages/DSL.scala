//package de.sciss.nuages
//
//import de.sciss.synth.{Server, GE}
//import de.sciss.synth.proc.impl.{ParamControlImpl, FactoryImpl, ProcImpl, GraphImpl, FactoryBuilderImpl}
//import de.sciss.synth
//import synth.proc.{ProcEntry, ProcParam, ProcParamAudioInput, ProcParamAudioOutput, ParamSpec, RichBus, Proc,
//   ProcGraph, ThreadLocalObject, ProcAnatomy, ProcDiff, ProcFilter, ProcGen, ProcFactoryBuilder, ProcDemiurg,
//   DSL => PDSL, ProcTxn, ProcFactory}
//import collection.immutable.{IndexedSeq => IIdxSeq}
//
///**
//*    @version 0.12, 27-Nov-10
//*/
//object NuagesDSL {
//   /**
//    * Generates a sound generating process factory
//    * with the given name and described through
//    * the given code block.
//    */
//   def ngen( name: String )( thunk: => Unit )( implicit tx: ProcTxn ) : ProcFactory = {
//      val res = NProcFactoryBuilder.gen( name )( thunk )
//      ProcDemiurg.addFactory( res )
//      res
//   }
//
//   /**
//    * Generates a sound filtering (transformation) process factory
//    * with the given name and described through
//    * the given code block.
//    */
//   def nfilter( name: String )( thunk: => Unit )( implicit tx: ProcTxn ) : ProcFactory = {
//      val res = NProcFactoryBuilder.filter( name )( thunk )
//      ProcDemiurg.addFactory( res )
//      res
//   }
//
//   /**
//    * Generates a sound diffusing process factory
//    * with the given name and described through
//    * the given code block.
//    */
//   def ndiff( name: String )( thunk: => Unit )( implicit tx: ProcTxn ) : ProcFactory = {
//      val res = NProcFactoryBuilder.diff( name )( thunk )
//      ProcDemiurg.addFactory( res )
//      res
//   }
//}
//
//object NProcFactoryBuilder {
//   def gen( name: String )( thunk: => Unit ) : ProcFactory =
//      apply( NFactoryBuilderImpl.gen( name ), thunk )
//
//   def filter( name: String )( thunk: => Unit ) : ProcFactory =
//      apply( NFactoryBuilderImpl.filter( name ), thunk )
//
//   def diff( name: String )( thunk: => Unit ) : ProcFactory =
//      apply( NFactoryBuilderImpl.diff( name ), thunk )
//
//   private def apply( b: ProcFactoryBuilder, thunk: => Unit ) : ProcFactory = ProcFactoryBuilder.use( b ) {
//      thunk
//      b.finish
//   }
//}
//
//object NFactoryBuilderImpl {
//   def gen( name: String ) : ProcFactoryBuilder =
//      new NFactoryBuilderImpl( name, ProcGen, false, true )
//
//   def filter( name: String ) : ProcFactoryBuilder =
//      new NFactoryBuilderImpl( name, ProcFilter, true, true )
//
//   def diff( name: String ) : ProcFactoryBuilder =
//      new NFactoryBuilderImpl( name, ProcDiff, true, false )
//}
//
//class NFactoryBuilderImpl private( _name: String, _anatomy: ProcAnatomy, _impAudioIn: Boolean, _impAudioOut: Boolean )
//extends FactoryBuilderImpl( _name, _anatomy, _impAudioIn, _impAudioOut ) {
//   private def meter( sig: GE ) {
//      import synth._
//      import ugen._
//
//      val numCh = sig.numOutputs
//      if( numCh < 1 ) return
//      Proc.local match {
//         case n: NProcImpl =>
//            val meterTr    = "$m_tr".tr
//            val meterIdx   = "$m_idx".kr
//            val trigA      = Trig1.ar( meterTr, SampleDur.ir )
//            val peak       = Peak.kr( sig, trigA ).outputs
//            val peakM      = peak.tail.foldLeft[ GE ]( peak.head )( _ max _ ) \ 0
//            val rms        = (Mix( Lag.ar( sig.squared, 0.1 )) / numCh) \ 0
//            Out.kr( meterIdx, peakM :: rms :: Nil )
//
//         case _ =>
//      }
////      val mix = Mix( sig )
//
//   }
////   protected def implicitOutAr( sig: GE ) : GE = {
////      val rate = Rate.highest( sig.outputs.map( _.rate ): _* )
////      if( (rate == audio) || (rate == control) ) {
////         Proc.local.param( "out" ).asInstanceOf[ ProcParamAudioOutput ].ar( sig )
////      } else sig
////   }
//
//   override def graphIn( fun: GE => GE ) : ProcGraph = {
//      val fullFun = () => {
//         val in = implicitInAr
//         meter( in )
//         fun( in )
//      }
//      graph( fullFun )
//   }
//
//   override def graphInOut( fun: GE => GE ) : ProcGraph = {
//      val fullFun = () => {
//         val in   = implicitInAr
//         val out  = fun( in )
//         meter( out )
//         implicitOutAr( out )
//      }
//      graph( fullFun )
//   }
//
//   override def graphOut( fun: () => GE ) : ProcGraph = {
//      val fullFun = () => {
//         val out = fun()
//         meter( out )
//         implicitOutAr( out )
//      }
//      graph( fullFun )
//   }
//
//   override def finish : ProcFactory = {
//      requireOngoing
//      require( entry.isDefined, "No entry point defined" )
//      if( _impAudioIn && !paramMap.contains( "in" )) {
//         pAudioIn( "in", None, false )
//      }
//      if( _impAudioOut && !paramMap.contains( "out" )) {
//         pAudioOut( "out", None, true )
//      }
//      finished = true
//      new NFactoryImpl( name, anatomy, entry.get, paramMap, paramSeq, pAudioIns, pAudioOuts )
//   }
//}
//
//class NFactoryImpl( _name: String, _anatomy: ProcAnatomy, _entry: ProcEntry,
//                    _paramMap: Map[ String, ProcParam ], _params: IIdxSeq[ ProcParam ],
//                   _pAudioIns: IIdxSeq[ ProcParamAudioInput ], _pAudioOuts: IIdxSeq[ ProcParamAudioOutput ])
//extends FactoryImpl( _name, _anatomy, _entry, _paramMap, _params, _pAudioIns, _pAudioOuts ) {
//   override def make( implicit tx: ProcTxn ) : Proc = {
//      val cnt = count()
//      count.set( cnt + 1 )
//      val res = new NProcImpl( this, cnt, Server.default )
//      ProcDemiurg.addVertex( res )
//      res
//   }
//}
//
//class NProcImpl( _fact: FactoryImpl, _cnt: Int, _server: Server )
//extends ProcImpl( _fact, _cnt, _server ) {
//   val meterBus   = RichBus.control( _server, 2 )
////   val meterParam = new ParamControlImpl( "$meter", ParamSpec( 0, 0xFFFF ), 0 )
//}