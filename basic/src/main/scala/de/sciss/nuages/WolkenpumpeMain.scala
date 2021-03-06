/*
 *  WolkenpumpeMain.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2021 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import de.sciss.file._
import de.sciss.lucre.{Cursor, Source}
import de.sciss.lucre.synth.{InMemory, Txn}
import de.sciss.osc
import de.sciss.proc.{AuralSystem, Universe}
import de.sciss.synth.{Server => SServer}

object WolkenpumpeMain {
  def main(args: Array[String]): Unit = {
    type S = InMemory
    type T = InMemory.Txn
    implicit val system: S = InMemory()
    val w = new WolkenpumpeMain[T]
    val nuagesH = system.step { implicit tx => tx.newHandle(Nuages.timeline[T]) }
    w.run(nuagesH)
  }
}
class WolkenpumpeMain[T <: Txn[T]] {
  private[this] var _view : NuagesView[T] = _
  private[this] var _frame: Option[NuagesFrame[T]] = _
  private[this] var _aural: AuralSystem   = _

  def view: NuagesView[T] = {
    if (_view == null) throw new IllegalStateException(s"NuagesView not yet initialized")
    _view
  }

  def frame: Option[NuagesFrame[T]] = {
    if (_frame == null) throw new IllegalStateException(s"NuagesFrame not yet initialized")
    _frame
  }

  def auralSystem: AuralSystem = {
    if (_aural == null) throw new IllegalStateException(s"AuralSystem not yet initialized")
    _aural
  }

  /** Subclasses may want to override this. */
  protected def configure(sCfg: ScissProcs.ConfigBuilder, nCfg: Nuages.ConfigBuilder,
                          aCfg: SServer.ConfigBuilder): Unit = {
    nCfg.mainChannels     = Some(0 to 7) // Vector(0, 1))
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
  protected def registerProcesses(nuages: Nuages[T], nCfg: Nuages.Config, sCfg: ScissProcs.Config)
                                 (implicit tx: T, universe: Universe[T]): Unit = {
    ScissProcs[T](nuages, nCfg, sCfg)
  }

  def run(nuagesH: Source[T, Nuages[T]])(implicit cursor: Cursor[T]): Unit = {
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
      nCfg.lineOutputs.map(_.stopOffset) :+ nCfg.soloChannels.fold(0)(_.max + 1) :+ nCfg.mainChannels.fold(0)(_.max + 1)
      ).max
    println(s"numInputs = $maxInputs, numOutputs = $maxOutputs")

    aCfg.outputBusChannels  = maxOutputs
    aCfg.inputBusChannels   = maxInputs

    cursor.step { implicit tx =>
      val n = nuagesH()
      implicit val universe: Universe[T] = Universe.dummy
      _aural = universe.auralSystem
      registerProcesses(n, nCfg, sCfg)
      _view = NuagesView(n, nCfg)
      _frame = if (nCfg.showFrame) {
        val f = NuagesFrame(_view, undecorated = false /* true */)
        Some(f)
      } else {
        None
      }
      if (nCfg.autoStart) _view.panel.transport.play()
      _aural.start(aCfg)
    }
  }
}