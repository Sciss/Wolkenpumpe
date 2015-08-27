package de.sciss.nuages
package impl

import de.sciss.lucre.stm
import de.sciss.lucre.swing.{defer, deferTx, requireEDT}
import de.sciss.lucre.synth.{Txn, Synth, Node, AudioBus, Sys}
import de.sciss.synth.proc.{Proc, AuralObj}
import de.sciss.synth.{message, addToTail, SynthGraph}

import PanelImpl.LAYOUT_TIME

import scala.concurrent.stm.{TMap, Ref}

trait PanelImplMixer[S <: Sys[S]] {
  // ---- abstract ----

  protected def main: NuagesPanel[S]

  protected def cursor: stm.Cursor[S]

  protected def auralToViewMap: TMap[AuralObj[S], VisualObj[S]]
  protected def viewToAuralMap: TMap[VisualObj[S], AuralObj[S]]

  protected def getAuralScanData(aural: AuralObj[S], key: String = Proc.scanMainOut)
                                (implicit tx: S#Tx): Option[(AudioBus, Node)]

  // ---- impl ----

  // simple cache from num-channels to graph
  private var meterGraphMap = Map.empty[Int, SynthGraph]
  private val soloVolume    = Ref(NuagesPanel.soloAmpSpec._2)  // 0.5
  private val soloInfo      = Ref(Option.empty[(VisualObj[S], Synth)])
  private val _masterSynth  = Ref(Option.empty[Synth])

  protected def mkMeter(bus: AudioBus, node: Node)(fun: Float => Unit)(implicit tx: S#Tx): Synth = {
    val numCh       = bus.numChannels
    val meterGraph  = meterGraphMap.getOrElse(numCh, {
      val res = SynthGraph {
        import de.sciss.synth._
        import de.sciss.synth.ugen._
        val meterTr = Impulse.kr(1000.0 / LAYOUT_TIME)
        val sig     = In.ar("in".kr, numCh)
        val peak    = Peak.kr(sig, meterTr) // .outputs
        val peakM   = Reduce.max(peak)
        SendTrig.kr(meterTr, peakM)
      }
      meterGraphMap += numCh -> res
      res
    })
    val meterSynth = Synth.play(meterGraph, Some("meter"))(node.server.defaultGroup, addAction = addToTail,
      dependencies = node :: Nil)
    meterSynth.read(bus -> "in")
    val NodeID = meterSynth.peer.id
    val trigResp = message.Responder.add(node.server.peer) {
      case m @ message.Trigger(NodeID, 0, peak: Float) =>
        defer(fun(peak))
    }
    // Responder.add is non-transactional. Thus, if the transaction fails, we need to remove it.
    scala.concurrent.stm.Txn.afterRollback { _ =>
      trigResp.remove()
    } (tx.peer)
    meterSynth.onEnd(trigResp.remove())
    meterSynth
  }

  def clearSolo()(implicit tx: S#Tx): Unit = {
    val oldInfo = soloInfo.swap(None)(tx.peer)
    oldInfo.foreach { case (oldVP, oldSynth) =>
      oldSynth.dispose()
      deferTx(oldVP.soloed = false)
    }
  }

  def setSolo(vp: VisualObj[S], onOff: Boolean): Unit = main.config.soloChannels.foreach { outChans =>
    requireEDT()
    cursor.step { implicit tx =>
      implicit val itx = tx.peer
      clearSolo()
      if (onOff) viewToAuralMap.get(vp).foreach { auralProc =>
        getAuralScanData(auralProc).foreach { case (bus, node) =>
          val sg = SynthGraph {
            import de.sciss.synth._
            import de.sciss.synth.ugen._
            val numIn     = bus.numChannels
            val numOut    = outChans.size
            // println(s"numIn = $numIn, numOut = $numOut")
            val in        = In.ar("in".kr, numIn)
            val amp       = "amp".kr(1f)
            val sigOut    = SplayAz.ar(numOut, in)
            val mix       = sigOut * amp
            outChans.zipWithIndex.foreach { case (ch, idx) =>
              ReplaceOut.ar(ch, mix \ idx)
            }
          }
          val soloSynth = Synth.play(sg, Some("solo"))(target = node.server.defaultGroup, addAction = addToTail,
            args = "amp" -> soloVolume() :: Nil, dependencies = node :: Nil)
          soloSynth.read(bus -> "in")
          soloInfo.set(Some(vp -> soloSynth))
        }
      }
      deferTx(vp.soloed = onOff)
    }
  }

  def masterSynth(implicit tx: Txn): Option[Synth] = _masterSynth.get(tx.peer)
  def masterSynth_=(value: Option[Synth])(implicit tx: Txn): Unit =
    _masterSynth.set(value)(tx.peer)

  def setMasterVolume(v: Double)(implicit tx: S#Tx): Unit =
    _masterSynth.get(tx.peer).foreach(_.set("amp" -> v))

  //    masterProc.foreach { pMaster =>
  //      // pMaster.control("amp").v = v
  //    }

  def setSoloVolume(v: Double)(implicit tx: S#Tx): Unit = {
    implicit val itx = tx.peer
    val oldV = soloVolume.swap(v)
    if (v == oldV) return
    soloInfo().foreach(_._2.set("amp" -> v))
  }
}