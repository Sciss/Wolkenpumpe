/*
 *  Wolkenpumpe.scala
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

import java.awt.Font

import de.sciss.lucre.synth.InMemory
import de.sciss.synth
import de.sciss.synth.proc
import de.sciss.synth.proc.{AuralSystem, ExprImplicits, Obj, Proc}
import proc.Implicits._

object Wolkenpumpe extends App with Runnable {
  run()

  def run(): Unit = {
    type S = InMemory
    implicit val system = InMemory()

    val config            = Nuages.Config()
    config.masterChannels = Some(Vector(0, 1))
    config.recordPath     = Some("/tmp")
    /* val f = */ system.step { implicit tx =>
      val n = Nuages.empty[S]
      val imp = ExprImplicits[S]

      import imp._
      import synth._
      import ugen._
      import proc.graph.{ScanIn, ScanOut, Attribute}

      val gen1          = Proc[S]
      val gen1Obj       = Obj(Proc.Elem(gen1))
      gen1Obj.attr.name = "Sprink"
      gen1.graph()      = SynthGraph {
        val freq = Attribute.ar("freq", 0.5)
        // ParamSpec(0.2, 50), 1)
        val out = BPZ2.ar(WhiteNoise.ar(LFPulse.ar(freq, 0, 0.25) * Seq(0.1, 0.1)))
        ScanOut("out", out)
      }
      n.generators.addLast(gen1Obj)

      val flt1          = Proc[S]
      val flt1Obj       = Obj(Proc.Elem(flt1))
      flt1Obj.attr.name = "Filt"
      flt1.graph()      = SynthGraph {
        // val pfreq = pAudio("freq", ParamSpec(-1, 1), 0.7)
        // val pmix = pAudio("mix", ParamSpec(0, 1), 1)
        val in        = ScanIn("in")
        val normFreq  = Attribute.ar("freq", 0.5)
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
        val mix       = Attribute.ar("mix", 1)
        val out       = LinXFade2.ar(in, flt, mix * 2 - 1)
        ScanOut("out", out)
      }
      n.filters.addLast(flt1Obj)

      val flt2          = Proc[S]
      val flt2Obj       = Obj(Proc.Elem(flt2))
      flt2Obj.attr.name = "Achil"
      flt2.graph()      = SynthGraph {
        val in = ScanIn("in")
        // val pspeed = pAudio("speed", ParamSpec(0.125, 2.3511, ExpWarp), 0.5)
        // val pmix    = pAudio( "mix", ParamSpec( 0, 1 ), 1 )

        val speed         = Lag.ar(Attribute.ar("speed", 1.0), 0.1)
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

        val mix           = Attribute.ar("mix", 1)

        LinXFade2.ar(in, read, mix * 2 - 1)
      }
      n.filters.addLast(flt2Obj)

      val col1          = Proc[S]
      val col1Obj       = Obj(Proc.Elem(col1))
      col1Obj.attr.name = "Out"
      col1.graph()      = SynthGraph {
        // val pamp = pAudio("amp", ParamSpec(0.01, 10, ExpWarp), 1)
        // val pout = pAudioOut("out", None) // Some( RichBus.wrap( masterBus ))

        val in  = ScanIn("in")
        val amp = Attribute.ar("gain", 1)
        val sig = in * amp
        // pout.ar(sig)
        Out.ar(0, sig)
      }
      n.collectors.addLast(col1Obj)

      val aural = AuralSystem.start()
      val p     = NuagesPanel(n, config, aural)
      NuagesFrame(p)
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
}