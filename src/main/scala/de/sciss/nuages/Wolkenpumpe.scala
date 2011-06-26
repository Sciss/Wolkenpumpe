/*
 *  Wolkenpumpe.scala
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

import de.sciss.synth.proc._
import collection.immutable.{ Set => ISet }
import de.sciss.synth._
import java.awt.event.{ActionEvent, ActionListener, KeyEvent, KeyAdapter}
import java.awt.{AWTEvent, BorderLayout, Color, Font}
import javax.swing.{AbstractAction, BorderFactory, JButton, JComponent, JLabel, JPanel, KeyStroke, WindowConstants}

//case class NuagesUpdate( gensAdded: ISet[ ProcFactory ], gensRemoved: ISet[ ProcFactory ],
//                         filtersAdded: ISet[ ProcFactory ], filtersRemoved: ISet[ ProcFactory ])

/**
 *    @version 0.13, 02-Aug-10
 */
object Wolkenpumpe /* extends TxnModel[ NuagesUpdate ]*/ {
   val name          = "Wolkenpumpe"
   val version       = 0.25
   val copyright     = "(C)opyright 2004-2011 Hanns Holger Rutz"
   def versionString = (version + 0.001).toString.substring( 0, 4 )

   def main( args: Array[ String ]) {
      if( args.size > 0 && args( 0 ) == "--test" ) {
         test
      } else {
         printInfo
         System.exit( 1 )
      }
   }

   def printInfo {
      println( "\n" + name + " v" + versionString + "\n" + copyright + ". All rights reserved.\n" +
         "This is a library which cannot be executed directly.\n" )
   }

   def test {
      var s : Server = null
      val booting = Server.boot() {
//         case ServerConnection.Preparing( srv ) =>
         case ServerConnection.Running( srv ) => {
            s = srv
//            srv.dumpOSC(1)
            ProcDemiurg.addServer( srv )
            val recordPath = "/tmp"
//            val masterBus  = new AudioBus( srv, 0, 2 )
//            val soloBus    = Bus.audio( srv, 2 )
//            val soloBus    = new AudioBus( srv, 6, 2 )
            val config = NuagesConfig( srv, Some( Vector( 0, 1 )), Some( Vector( 2, 3 )), Some( recordPath ), true )
            val f = new NuagesFrame( config )
            val p = f.panel
//            p.addKeyListener( new TestKeyListener( p ))
            f.setDefaultCloseOperation( WindowConstants.EXIT_ON_CLOSE )
            f.setSize( 640, 480 )
            f.setVisible( true )
            ProcTxn.atomic { implicit tx =>
               import de.sciss.synth.proc.DSL._
               import de.sciss.synth._
               import de.sciss.synth.ugen._

               gen( "Sprink" ) {
                  val pfreq = pAudio( "freq", ParamSpec( 0.2, 50 ), 1 )
                  graph {
                     BPZ2.ar( WhiteNoise.ar( LFPulse.ar( pfreq.ar, 0, 0.25 ) * List( 0.1, 0.1 )))
                  }
               }

               filter( "Filt" ) {
                  val pfreq = pAudio( "freq", ParamSpec( -1, 1 ), 0.7 )
                  val pmix  = pAudio( "mix", ParamSpec( 0, 1 ), 1 )

                  graph { in =>
                     val normFreq   = pfreq.ar
                     val lowFreqN	= Lag.ar( Clip.ar( normFreq, -1, 0 ))
                     val highFreqN  = Lag.ar( Clip.ar( normFreq,  0, 1 ))
                     val lowFreq	   = LinExp.ar( lowFreqN, -1, 0, 30, 20000 )
                     val highFreq   = LinExp.ar( highFreqN, 0, 1, 30, 20000 )
                     val lowMix	   = Clip.ar( lowFreqN * -10.0, 0, 1 )
                     val highMix	   = Clip.ar( highFreqN * 10.0, 0, 1 )
                     val dryMix	   = 1 - (lowMix + highMix)
                     val lpf		   = LPF.ar( in, lowFreq ) * lowMix
                     val hpf		   = HPF.ar( in, highFreq ) * highMix
                     val dry		   = in * dryMix
                     val flt		   = dry + lpf + hpf
                     LinXFade2.ar( in, flt, pmix.ar * 2 - 1 )
                  }
               }

               filter( "Achil") {
                  val pspeed  = pAudio( "speed", ParamSpec( 0.125, 2.3511, ExpWarp ), 0.5 )
                  val pmix    = pAudio( "mix", ParamSpec( 0, 1 ), 1 )

                  graph { in =>
                     val speed	   = Lag.ar( pspeed.ar, 0.1 )
                     val numFrames  = sampleRate.toInt
                     val numChannels= in.numOutputs
                     val buf        = bufEmpty( numFrames, numChannels )
                     val bufID      = buf.id
                     val writeRate  = BufRateScale.kr( bufID )
                     val readRate   = writeRate * speed
                     val readPhasor = Phasor.ar( 0, readRate, 0, numFrames )
                     val read			= BufRd.ar( numChannels, bufID, readPhasor, 0, 4 )
                     val writePhasor= Phasor.ar( 0, writeRate, 0, numFrames )
                     val old			= BufRd.ar( numChannels, bufID, writePhasor, 0, 1 )
                     val wet0 		= SinOsc.ar( 0, ((readPhasor - writePhasor).abs / numFrames * math.Pi) )
                     val dry			= 1 - wet0.squared
                     val wet			= 1 - (1 - wet0).squared
                     BufWr.ar( (old * dry) + (in * wet), bufID, writePhasor )
                     LinXFade2.ar( in, read, pmix.ar * 2 - 1 )
                  }
               }

               diff( "Out" ) {
                   val pamp  = pAudio( "amp", ParamSpec( 0.01, 10, ExpWarp ), 1 )
                   val pout  = pAudioOut( "out", None ) // Some( RichBus.wrap( masterBus ))

                   graph { in =>
                      val sig = in * pamp.ar
//                      Out.ar( masterBus.index, sig )
                      pout.ar( sig )
                   }
               }
            }
         }
      }
      Runtime.getRuntime().addShutdownHook( new Thread { override def run = {
         if( (s != null) && (s.condition != Server.Offline) ) {
            s.quit
            s = null
         } else {
            booting.abort
         }
      }})
//      booting.start
   }

   /**
    *    A condensed font for GUI usage. This is in 12 pt size,
    *    so consumers must rescale.
    */
   /*lazy val*/ var condensedFont : Font = {
// createFont doesn't properly create the spacing. fucking hell...
//      val is   = Wolkenpumpe.getClass.getResourceAsStream( "BellySansCondensed.ttf" )
//      val res  = Font.createFont( Font.TRUETYPE_FONT, is )
//      is.close
//      res
      // "SF Movie Poster Condensed"
      new Font( "BellySansCondensed", Font.PLAIN, 12 )
   }

   private class TestKeyListener( p: NuagesPanel ) extends KeyAdapter {
//      println( "TestKeyListener" )
      val imap = p.display.getInputMap( JComponent.WHEN_IN_FOCUSED_WINDOW )
      val amap = p.display.getActionMap
      p.requestFocus
      var paneO = Option.empty[ JPanel ]
      imap.put( KeyStroke.getKeyStroke( ' ' ), "pop" )
      amap.put( "pop", new AbstractAction( "popup" ) {
         def actionPerformed( e: ActionEvent ) {
//            println( "POP!" )
            if( paneO.isDefined ) return
            val pane = new JPanel( new BorderLayout() ) {
               import AWTEvent._
               val masks = MOUSE_EVENT_MASK | MOUSE_MOTION_EVENT_MASK | MOUSE_WHEEL_EVENT_MASK
               enableEvents( masks )
               override protected def processEvent( e: AWTEvent ) {
                  val id = e.getID
                  if( (id & masks) == 0 ) {
                     super.processEvent( e )
                  }
               }
            }
            pane.setOpaque( true )
            pane.setBorder( BorderFactory.createCompoundBorder( BorderFactory.createMatteBorder( 1, 1, 1, 1, Color.white ),
               BorderFactory.createEmptyBorder( 4, 4, 4, 4 )))
            val lb = new JLabel( "Hallo Welt" )
            pane.add( lb, BorderLayout.NORTH )
            val but = new JButton( "Close" )
            but.setFocusable( false )
            pane.add( but, BorderLayout.SOUTH )
            pane.setSize( pane.getPreferredSize )
            pane.setLocation( 200, 200 )
            p.add( pane, 0 )
            paneO = Some( pane )
            but.addActionListener( new ActionListener {
               def actionPerformed( e: ActionEvent ) {
                  p.remove( pane )
                  paneO = None
               }
            })
         }
      })
   }
}