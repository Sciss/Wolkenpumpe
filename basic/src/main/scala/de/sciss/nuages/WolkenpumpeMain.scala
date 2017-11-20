/*
 *  WolkenpumpeMain.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2017 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.file._
import de.sciss.lucre.stm
import de.sciss.lucre.synth.{InMemory, Sys}
import de.sciss.osc
import de.sciss.synth.proc.{AuralSystem, Code, Compiler, SoundProcesses}
import de.sciss.synth.{Server => SServer}

import scala.concurrent.Future
import scala.util.{Failure, Success}

object WolkenpumpeMain {
  def main(args: Array[String]): Unit = {
    type S = InMemory
    implicit val system: S = InMemory()
    val w = new WolkenpumpeMain[S]
    val nuagesH = system.step { implicit tx => tx.newHandle(Nuages.timeline[S]) }
    w.run(nuagesH)
  }
}
class WolkenpumpeMain[S <: Sys[S]] {
  private[this] var _view : NuagesView[S] = _
  private[this] var _aural: AuralSystem   = _

  def view: NuagesView[S] = {
    if (_view == null) throw new IllegalStateException(s"NuagesView not yet initialized")
    _view
  }

  def auralSystem: AuralSystem = {
    if (_aural == null) throw new IllegalStateException(s"AuralSystem not yet initialized")
    _aural
  }

  /** Subclasses may want to override this. */
  protected def configure(sCfg: ScissProcs.ConfigBuilder, nCfg: Nuages.ConfigBuilder,
                          aCfg: SServer.ConfigBuilder): Unit = {
    nCfg.masterChannels     = Some(0 to 7) // Vector(0, 1))
    nCfg.soloChannels       = None // Some(0 to 1)

    nCfg.micInputs          = Vector(
      NamedBusConfig("m-dpa"  , 0 until 1),
      NamedBusConfig("m-at "  , 3 until 4)
    )
    nCfg.lineInputs         = Vector(NamedBusConfig("pirro", 2 until 3))
    nCfg.lineOutputs        = Vector(
      NamedBusConfig("sum", 5 until 6)
      // , NamedBusConfig("hp", 6, 2)  // while 'solo' doesn't work
    )

    sCfg.audioFilesFolder   = Some(userHome / "Music" / "tapes")
  }

  /** Subclasses may want to override this. */
  protected def registerProcesses(nuages: Nuages[S], nCfg: Nuages.Config, sCfg: ScissProcs.Config)
                                 (implicit tx: S#Tx, cursor: stm.Cursor[S],
                                  compiler: Code.Compiler): Future[Unit] = {
    ScissProcs.compileAndApply[S](nuages, nCfg, sCfg)
  }

  def run(nuagesH: stm.Source[S#Tx, Nuages[S]])(implicit cursor: stm.Cursor[S]): Unit = {
    Wolkenpumpe.init()

    val nCfg                = Nuages    .Config()
    val sCfg                = ScissProcs.Config()
    val aCfg                = SServer   .Config()

    nCfg.recordPath         = Option(sys.props("java.io.tmpdir"))
    aCfg.deviceName         = Some("Wolkenpumpe")
    aCfg.audioBusChannels   = 512
    aCfg.memorySize         = 256 * 1024
    aCfg.transport          = osc.TCP
    aCfg.pickPort()

    configure(sCfg, nCfg, aCfg)

    val maxInputs   = ((nCfg.lineInputs ++ nCfg.micInputs).map(_.stopOffset) :+ 0).max
    val maxOutputs  = (
      nCfg.lineOutputs.map(_.stopOffset) :+ nCfg.soloChannels.fold(0)(_.max + 1) :+ nCfg.masterChannels.fold(0)(_.max + 1)
      ).max
    println(s"numInputs = $maxInputs, numOutputs = $maxOutputs")

    aCfg.outputBusChannels  = maxOutputs
    aCfg.inputBusChannels   = maxInputs

    implicit val compiler: Code.Compiler = Compiler()

    val futPrep = cursor.step { implicit tx =>
      val n = nuagesH()
      _aural = AuralSystem()
      registerProcesses(n, nCfg, sCfg)
    }

    import SoundProcesses.executionContext
    futPrep.onComplete {
      case Success(_) =>
        cursor.step { implicit tx =>
          val n = nuagesH()
          import de.sciss.synth.proc.WorkspaceHandle.Implicits._
          implicit val aural: AuralSystem = _aural
            _view = NuagesView(n, nCfg)
          /* val frame = */ NuagesFrame(_view, undecorated = false /* true */)
          aural.start(aCfg)
        }

      case Failure(ex) =>
        Console.err.println("Wolkenpumpe, failed to initialize:")
        ex.printStackTrace()
    }
  }
}