/*
 *  TransportViewImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2015 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages.impl

import java.awt.event.ActionEvent

import de.sciss.audiowidgets.{TimelineModel, Transport => GUITransport}
import de.sciss.lucre.stm.{Cursor, Disposable}
import de.sciss.lucre.swing.{View, deferTx}
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.synth.Sys
import de.sciss.synth.proc.gui.TimeDisplay
import de.sciss.synth.proc.gui.impl.CursorHolder
import de.sciss.synth.proc.{Timeline, Transport}

import scala.swing.Swing._
import scala.swing.{BoxPanel, Component, Orientation, Swing}

// XXX TODO - I think this code exists elsewhere
object TransportViewImpl {
  def apply[S <: Sys[S]](transport: Transport[S] /* .Realtime[S, Obj.T[S, Proc.Elem], Transport.Proc.Update[S]] */,
                         model: TimelineModel)
                        (implicit tx: S#Tx, cursor: Cursor[S]): View[S] = {
    val view    = new Impl(transport, model)
    val srk     = 1000 / Timeline.SampleRate // transport.sampleRate

    view.observer = transport.react { implicit tx => {
      case Transport.Play(_, time) => view.startedPlaying(time)
      case Transport.Stop(_, time) => view.stoppedPlaying(time)
      case Transport.Seek(_, time, p) =>
        if (p) view.startedPlaying(time) else view.stoppedPlaying(time)
      case _ =>
    }}

    val initPlaying = transport.isPlaying // .playing.value
    val initMillis = (transport.position * srk).toLong
    deferTx {
      view.guiInit(initPlaying, initMillis)
    }
    view
  }

  private final class Impl[S <: Sys[S]](val transport: Transport[S] /* .Realtime[S, Obj.T[S, Proc.Elem], Transport.Proc.Update[S]] */,
                                        val timelineModel: TimelineModel)
                                       (implicit protected val cursor: Cursor[S])
    extends View[S] with ComponentHolder[Component] with CursorHolder[S] {

    private val modOpt  = timelineModel.modifiableOption

    var observer: Disposable[S#Tx] = _

    private var playTimer: javax.swing.Timer = _

    private var timerFrame  = 0L
    private var timerSys    = 0L
    private val srm         = 0.001 * Timeline.SampleRate // transport.sampleRate

    private var transportStrip: Component with GUITransport.ButtonStrip = _

    def dispose()(implicit tx: S#Tx): Unit = {
      observer.dispose()
      deferTx {
        playTimer.stop()
      }
    }

    // ---- transport ----

    def startedPlaying(time: Long)(implicit tx: S#Tx): Unit =
      deferTx {
        playTimer.stop()
        timerFrame  = time
        timerSys    = System.currentTimeMillis()
        playTimer.start()
        transportStrip.button(GUITransport.Play).foreach(_.selected = true )
        transportStrip.button(GUITransport.Stop).foreach(_.selected = false)
      }

    def stoppedPlaying(time: Long)(implicit tx: S#Tx): Unit =
      deferTx {
        playTimer.stop()
        // cueTimer .stop()
        modOpt.foreach(_.position = time) // XXX TODO if Cursor follows play-head
        transportStrip.button(GUITransport.Play).foreach(_.selected = false)
        transportStrip.button(GUITransport.Stop).foreach(_.selected = true )
      }

    //    private def playOrStop(): Unit =
    //      atomic { implicit tx =>
    //        if (transport.isPlaying) transport.stop() else {
    //          transport.seek(timelineModel.position)
    //          transport.play()
    //        }
    //      }

    private def stop(): Unit =
      atomic { implicit tx => transport.stop() }

    private def play(): Unit =
      atomic { implicit tx =>
        transport.stop()
        transport.seek(timelineModel.position)
        transport.play()
      }

    def guiInit(initPlaying: Boolean, initMillis: Long): Unit = {
      val timeDisplay = TimeDisplay(timelineModel, hasMillis = false)

      import de.sciss.audiowidgets.Transport.{Action => _, _}
      val actions0 = Vector(
        Stop        { stop        () },
        Play        { play        () }
      )
      val actions1 = actions0
      transportStrip = GUITransport.makeButtonStrip(actions1)
      transportStrip.button(Stop).foreach(_.selected = true)

      val transportPane = new BoxPanel(Orientation.Horizontal) {
        contents += timeDisplay.component
        contents += HStrut(8)
        contents += transportStrip
      }
      //      transportPane.addAction("play-stop", focus = FocusType.Window, action = new Action("play-stop") {
      ////        accelerator = Some(KeyStrokes.plain + Key.Space)
      //        def apply(): Unit = playOrStop()
      //      })

      playTimer = new javax.swing.Timer(47,
        Swing.ActionListener(modOpt.fold((_: ActionEvent) => ()) { mod => (e: ActionEvent) =>
          val elapsed = ((System.currentTimeMillis() - timerSys) * srm).toLong
          mod.position = timerFrame + elapsed
        })
      )

      component = transportPane
    }
  }
}
