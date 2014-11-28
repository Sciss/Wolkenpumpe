/*
 *  Wolkenpumpe.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2014 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import java.awt.Font

import de.sciss.file._
import de.sciss.lucre.stm
import de.sciss.lucre.swing.defer
import de.sciss.lucre.synth.{Synth, Server, Txn, Sys, InMemory}
import de.sciss.lucre.expr.{Double => DoubleEx}
import de.sciss.{osc, synth}
import de.sciss.synth.proc.graph.{Attribute, ScanIn, ScanOut}
import de.sciss.synth.{Server => SServer, addAfter, control, scalar, audio, Rate, SynthGraph, GE, proc}
import de.sciss.synth.message
import de.sciss.synth.proc.{Folder, WorkspaceHandle, DoubleElem, AuralSystem, ExprImplicits, Obj, Proc}
import proc.Implicits._

import scala.collection.breakOut
import scala.concurrent.stm.TxnLocal

object Wolkenpumpe {
  def main(args: Array[String]): Unit = {
    type S = InMemory
    implicit val system = InMemory()
    run[S]()
  }

  def initTypes(): Unit = {
    ParamSpec
  }

  class DSL[S <: Sys[S]] {
    val imp = ExprImplicits[S]
    import imp._

    private val current = TxnLocal[Proc.Obj[S]]()

    private def mkObj(name: String)(fun: => Unit)(implicit tx: S#Tx, n: Nuages[S]): Proc.Obj[S] = {
      val p     = Proc[S]
      val obj   = Obj(Proc.Elem(p))
      obj.name  = name
      current.set(obj)(tx.peer)
      p.graph() = SynthGraph { fun }
      current.set(null)(tx.peer)
      obj
    }

    def pAudio(key: String, spec: ParamSpec, default: Double)(implicit tx: S#Tx): GE =
      mkPar(audio, key = key, spec = spec, default = default)

    def pControl(key: String, spec: ParamSpec, default: Double)(implicit tx: S#Tx): GE =
      mkPar(control, key = key, spec = spec, default = default)

    def pScalar(key: String, spec: ParamSpec, default: Double)(implicit tx: S#Tx): GE =
      mkPar(scalar, key = key, spec = spec, default = default)

    private def mkPar(rate: Rate, key: String, spec: ParamSpec, default: Double)(implicit tx: S#Tx): GE = {
      val obj       = current.get(tx.peer)
      val paramObj  = Obj(DoubleElem(DoubleEx.newVar(DoubleEx.newConst[S](default))))
      val specObj   = Obj(ParamSpec.Elem(ParamSpec.Expr.newConst[S](spec)))
      paramObj.attr.put(ParamSpec.Key, specObj)
      obj     .attr.put(key, paramObj)
      Attribute(rate, key, default)
    }

    private def insertByName(folder: Folder[S], elem: Obj[S])(implicit tx: S#Tx): Unit = {
      val nameL = elem.name.toLowerCase
      val idx0  = folder.iterator.toList.indexWhere(_.name.toLowerCase.compareTo(nameL) > 0)
      val idx   = if (idx0 >= 0) idx0 else folder.size
      folder.insert(idx, elem)
    }

    def generator(name: String)(fun: => GE)(implicit tx: S#Tx, n: Nuages[S]): Proc.Obj[S] = {
      val obj = mkObj(name) {
        val out = fun
        ScanOut("out", out)
      }
      obj.elem.peer.scans.add("out")
      insertByName(n.generators, obj)
      obj
    }

    def filter(name: String)(fun: GE => GE)(implicit tx: S#Tx, n: Nuages[S]): Proc.Obj[S] = {
      val obj = mkObj(name) {
        val in  = ScanIn("in")
        val out = fun(in)
        ScanOut("out", out)
      }
      val scans = obj.elem.peer.scans
      scans.add("in" )
      scans.add("out")
      insertByName(n.filters, obj)
      obj
    }

    def sink(name: String)(fun: GE => Unit)(implicit tx: S#Tx, n: Nuages[S]): Proc.Obj[S] =
      sinkLike(n.filters, name, fun)

    def collector(name: String)(fun: GE => Unit)(implicit tx: S#Tx, n: Nuages[S]): Proc.Obj[S] =
      sinkLike(n.collectors, name, fun)

    private def sinkLike(folder: Folder[S], name: String, fun: GE => Unit)
                        (implicit tx: S#Tx, nuages: Nuages[S]): Proc.Obj[S] = {
      val obj = mkObj(name) {
        val in = ScanIn("in")
        fun(in)
      }
      obj.elem.peer.scans.add("in")
      insertByName(folder, obj)
      obj
    }

    // def prepare(obj: Obj[S])(fun: S#Tx => Obj[S] => Unit): Unit = ...
  }

  def mkTestProcs[S <: Sys[S]]()(implicit tx: S#Tx, nuages: Nuages[S]): Unit = {
    val dsl = new DSL[S]
    import dsl._

    import synth.{Server => _, _}
    import ugen._

    generator("Sprink") {
      val freq = pAudio("freq", ParamSpec(0.2, 50), 1)
      BPZ2.ar(WhiteNoise.ar(LFPulse.ar(freq, 0, 0.25) * Seq(0.1, 0.1)))
    }

    filter("Filt") { in =>
      val normFreq  = pAudio("freq", ParamSpec(-1, 1), 0.7)
      val lowFreqN  = Lag.ar(Clip.ar(normFreq, -1, 0))
      val highFreqN = Lag.ar(Clip.ar(normFreq, 0, 1))
      val lowFreq   = LinExp.ar(lowFreqN, -1, 0, 30, 20000)
      val highFreq  = LinExp.ar(highFreqN, 0, 1, 30, 20000)
      val lowMix    = Clip.ar(lowFreqN * -10.0, 0, 1)
      val highMix   = Clip.ar(highFreqN * 10.0, 0, 1)
      val dryMix    = 1 - (lowMix + highMix)
      val lpf       = LPF.ar(in, lowFreq) * lowMix
      val hpf       = HPF.ar(in, highFreq) * highMix
      val dry       = in * dryMix
      val flt       = dry + lpf + hpf
      val mix       = pAudio("mix", ParamSpec(0, 1), 0 /* 1 */)
      LinXFade2.ar(in, flt, mix * 2 - 1)
    }

    filter("Achil") { in =>
      val speed         = Lag.ar(pAudio("speed", ParamSpec(0.125, 2.3511, ExponentialWarp), 0.5), 0.1)
      val numFrames     = 44100 // sampleRate.toInt
      val numChannels   = 2     // in.numChannels // numOutputs
      //println( "numChannels = " + numChannels )

      // val buf           = bufEmpty(numFrames, numChannels)
      // val bufID         = buf.id
      val bufID         = LocalBuf(numFrames = numFrames, numChannels = numChannels)

      val writeRate     = BufRateScale.kr(bufID)
      val readRate      = writeRate * speed
      val readPhasor    = Phasor.ar(0, readRate, 0, numFrames)
      val read          = BufRd.ar(numChannels, bufID, readPhasor, 0, 4)
      val writePhasor   = Phasor.ar(0, writeRate, 0, numFrames)
      val old           = BufRd.ar(numChannels, bufID, writePhasor, 0, 1)
      val wet0          = SinOsc.ar(0, (readPhasor - writePhasor).abs / numFrames * math.Pi)
      val dry           = 1 - wet0.squared
      val wet           = 1 - (1 - wet0).squared
      BufWr.ar((old * dry) + (in * wet), bufID, writePhasor)

      val mix           = pAudio("mix", ParamSpec(0, 1), 1)

      LinXFade2.ar(in, read, mix * 2 - 1)
    }

    collector("Out") { in =>
      val amp = pAudio("amp", ParamSpec(-inf, 20, DbFaderWarp), -inf).dbamp
      val sig = in * amp
      Out.ar(0, sig)
    }
  }

  def run[S <: Sys[S]]()(implicit cursor: stm.Cursor[S]): Unit = {
    initTypes()

    val nCfg                = Nuages.Config()
    nCfg.masterChannels     = Some(0 until 5) // Vector(0, 1))
    nCfg.recordPath         = Some("/tmp")
    nCfg.soloChannels       = Some(0 to 1)
    nCfg.collector          = false   // not yet fully supported

    val sCfg = ScissProcs.Config()
    sCfg.audioFilesFolder   = Some(userHome / "Music" / "tapes")
    sCfg.micInputs          = Vector(
      NamedBusConfig("m-dpa"  , 0, 1),
      NamedBusConfig("m-at "  , 3, 1)
    )
    sCfg.lineInputs         = Vector(NamedBusConfig("pirro", 2, 1))
    sCfg.lineOutputs        = Vector(
      NamedBusConfig("sum", 5, 1)
      , NamedBusConfig("hp", 6, 2)  // while 'solo' doesn't work
    )

    val aCfg                = SServer.Config()
    aCfg.deviceName         = Some("Wolkenpumpe")
    aCfg.audioBusChannels   = 512
    aCfg.outputBusChannels  = 8
    aCfg.inputBusChannels   = 8
    aCfg.memorySize         = 256 * 1024
    aCfg.transport          = osc.TCP
    aCfg.pickPort()

    /* val f = */ cursor.step { implicit tx =>
      implicit val n      = Nuages.empty[S]
      implicit val aural  = AuralSystem()

      ScissProcs[S](sCfg, nCfg)

      import WorkspaceHandle.Implicits._
      val p     = NuagesPanel(n, nCfg, aural)
      val numIn = sCfg.lineInputs.size + sCfg.micInputs.size
      val frame = NuagesFrame(p, numInputChannels = numIn, undecorated = true)

      aural.addClient(new AuralSystem.Client {
        def auralStarted(server: Server)(implicit tx: Txn): Unit =
          installMasterSynth(server, nCfg, sCfg, frame)

        def auralStopped()(implicit tx: Txn): Unit = ()
      })

      aural.start(aCfg)
    }

    //        val recordPath = "/tmp"
    //        //            val masterBus  = new AudioBus( srv, 0, 2 )
    //        //            val soloBus    = Bus.audio( srv, 2 )
    //        //            val soloBus    = new AudioBus( srv, 6, 2 )
    //        val config = NuagesConfig(srv, Some(Vector(0, 1)), Some(Vector(2, 3)), Some(recordPath), meters = true)
    //        val f = new NuagesFrame(config)
    //        //            val p = f.panel
    //        //            p.addKeyListener( new TestKeyListener( p ))
    //        f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE)
    //        f.setSize(640, 480)
    //        f.setVisible(true)
  }

  /** A condensed font for GUI usage. This is in 12 pt size,
    * so consumers must rescale.
    */
  /*lazy val*/ var condensedFont: Font = {
    // NOT ANY MORE: createFont doesn't properly create the spacing. fucking hell...
    val is = Wolkenpumpe.getClass.getResourceAsStream("BellySansCondensed.ttf")
    val res = Font.createFont(Font.TRUETYPE_FONT, is)
    is.close()
    res
    //      // "SF Movie Poster Condensed"
    //      new Font( "BellySansCondensed", Font.PLAIN, 12 )
  }

  //   private class TestKeyListener( p: NuagesPanel ) extends KeyAdapter {
  ////      println( "TestKeyListener" )
  //      val imap = p.display.getInputMap( JComponent.WHEN_IN_FOCUSED_WINDOW )
  //      val amap = p.display.getActionMap
  //      p.requestFocus
  //      var paneO = Option.empty[ JPanel ]
  //      imap.put( KeyStroke.getKeyStroke( InputEvent.VK_F ))
  //
  ////      imap.put( KeyStroke.getKeyStroke( ' ' ), "pop" )
  ////      amap.put( "pop", new AbstractAction( "popup" ) {
  ////         def actionPerformed( e: ActionEvent ) {
  //////            println( "POP!" )
  ////            if( paneO.isDefined ) return
  ////            val pane = new JPanel( new BorderLayout() ) {
  ////               import AWTEvent._
  ////               val masks = MOUSE_EVENT_MASK | MOUSE_MOTION_EVENT_MASK | MOUSE_WHEEL_EVENT_MASK
  ////               enableEvents( masks )
  ////               override protected def processEvent( e: AWTEvent ) {
  ////                  val id = e.getID
  ////                  if( (id & masks) == 0 ) {
  ////                     super.processEvent( e )
  ////                  }
  ////               }
  ////            }
  ////            pane.setOpaque( true )
  ////            pane.setBorder( BorderFactory.createCompoundBorder( BorderFactory.createMatteBorder( 1, 1, 1, 1, Color.white ),
  ////               BorderFactory.createEmptyBorder( 4, 4, 4, 4 )))
  ////            val lb = new JLabel( "Hallo Welt" )
  ////            pane.add( lb, BorderLayout.NORTH )
  ////            val but = new JButton( "Close" )
  ////            but.setFocusable( false )
  ////            pane.add( but, BorderLayout.SOUTH )
  ////            pane.setSize( pane.getPreferredSize )
  ////            pane.setLocation( 200, 200 )
  ////            p.add( pane, 0 )
  ////            paneO = Some( pane )
  ////            but.addActionListener( new ActionListener {
  ////               def actionPerformed( e: ActionEvent ) {
  ////                  p.remove( pane )
  ////                  paneO = None
  ////               }
  ////            })
  ////         }
  ////      })
  //   }

  private def installMasterSynth[S <: Sys[S]](server: Server, nConfig: Nuages.Config, sConfig: ScissProcs.Config,
                                 frame: NuagesFrame[S])
                                (implicit tx: Txn): Unit = {
    val dfPostM = SynthGraph {
      import synth._; import ugen._
      // val masterBus = settings.frame.panel.masterBus.get // XXX ouch
      // val sigMast = In.ar( masterBus.index, masterBus.numChannels )
      val masterBus   = nConfig.masterChannels.getOrElse(Vector.empty)
      val sigMast0    = masterBus.map(ch => In.ar(ch))
      val sigMast: GE = sigMast0
      // external recorders
      sConfig.lineOutputs.foreach { cfg =>
        val off     = cfg.offset
        val numOut  = cfg.numChannels
        val numIn   = masterBus.size // numChannels
        val sig1: GE = if (numOut == numIn) {
          sigMast
        } else if (numIn == 1) {
          Seq.fill[GE](numOut)(sigMast)
        } else {
          val sigOut = SplayAz.ar(numOut, sigMast)
          Limiter.ar(sigOut, (-0.2).dbamp)
        }
        //            assert( sig1.numOutputs == numOut )
        Out.ar(off, sig1)
      }
      // master + people meters
      val meterTr    = Impulse.kr(20)
      val (peoplePeak, peopleRMS) = {
        val groups = /* if( NuagesApp.METER_MICS ) */ sConfig.micInputs ++ sConfig.lineInputs // else sConfig.lineInputs
        val res = groups.map { cfg =>
          val off        = cfg.offset
          val numIn      = cfg.numChannels
          val pSig       = In.ar(NumOutputBuses.ir + off, numIn)
          val peak       = Peak.kr(pSig, meterTr) // .outputs
          val peakM      = Reduce.max(peak)
          val rms        = A2K.kr(Lag.ar(pSig.squared, 0.1))
          val rmsM       = Mix.mono(rms) / numIn
          (peakM, rmsM)
        }
        (res.map( _._1 ): GE) -> (res.map( _._2 ): GE)  // elegant it's not
      }
      val masterPeak    = Peak.kr( sigMast, meterTr )
      val masterRMS     = A2K.kr( Lag.ar( sigMast.squared, 0.1 ))
      val peak: GE      = Flatten( Seq( masterPeak, peoplePeak ))
      val rms: GE       = Flatten( Seq( masterRMS, peopleRMS ))
      val meterData     = Zip( peak, rms )  // XXX correct?
      SendReply.kr( meterTr, meterData, "/meters" )

      val amp = "amp".kr(1f)
      (masterBus zip sigMast0).foreach { case (ch, sig) =>
        ReplaceOut.ar(ch, Limiter.ar(sig * amp))
      }
    }
    val synPostM = Synth.play(dfPostM, Some("post-master"))(server.defaultGroup, addAction = addAfter)

    frame.view.masterSynth = Some(synPostM)

    val synPostMID = synPostM.peer.id
    message.Responder.add(server.peer) {
      case osc.Message( "/meters", `synPostMID`, 0, values @ _* ) =>
        defer {
          val ctrl = frame.controlPanel
          ctrl.meterUpdate(values.map(_.asInstanceOf[Float])(breakOut))
        }
    }
  }
}