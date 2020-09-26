/*
 *  PanelImplMixer.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2020 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.lucre.swing.LucreSwing.defer
import de.sciss.lucre.synth.{AudioBus, RT, Synth, Txn, Node => SNode}
import de.sciss.nuages.impl.PanelImpl.LAYOUT_TIME
import de.sciss.osc
import de.sciss.synth.{SynthGraph, addToTail, message}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.{InTxn, Ref}

trait PanelImplMixer[T <: Txn[T]] {
  _: NuagesPanel[T] =>

  // ---- abstract ----

  protected def main: NuagesPanel[T]

  // ---- impl ----

  // simple cache from num-channels to graph.
  // no need to make these transactional, cache misses are ok here
  private[this] var monitorGraphMap     = Map.empty[Int, SynthGraph]
  private[this] var peakMeterGraphMap   = Map.empty[Int, SynthGraph]
  private[this] var valueMeterGraphMap  = Map.empty[Int, SynthGraph]
  private[this] val soloVolume          = Ref(NuagesPanel.soloAmpSpec._2)  // 0.5
  private[this] val soloObj             = Ref(Option.empty[NuagesObj[T]])
  private[this] val _soloSynth          = Ref(Option.empty[Synth])
  private[this] val _mainSynth          = Ref(Option.empty[Synth])

  final def mkPeakMeter(bus: AudioBus, node: SNode)(fun: Double => Unit)(implicit tx: T): Synth = {
    val numCh  = bus.numChannels
    val graph  = peakMeterGraphMap.getOrElse(numCh, {
      val res = SynthGraph {
        import de.sciss.synth._
        import Ops._
        import ugen._
        val meterTr = Impulse.kr(1000.0 / LAYOUT_TIME)
        val sig     = In.ar("in".kr, numCh)
        val peak    = Peak.kr(sig, meterTr) // .outputs
        val peakM   = Reduce.max(peak)
        SendTrig.kr(meterTr, peakM)
      }
      peakMeterGraphMap += numCh -> res
      res
    })
    val server  = node.server
    val syn     = Synth.play(graph, Some("peak"))(server.defaultGroup, addAction = addToTail,
      dependencies = node :: Nil)
    syn.read(bus -> "in")
    val NodeId = syn.peer.id
    val trigResp = message.Responder.add(server.peer) {
      case message.Trigger(NodeId, 0, peak: Float) => defer(fun(peak))
    }
    // Responder.add is non-transactional. Thus, if the transaction fails, we need to remove it.
    scala.concurrent.stm.Txn.afterRollback { _ =>
      trigResp.remove()
    } (tx.peer)
    syn.onEnd(trigResp.remove())
    syn
  }

  final def mkSoloSynth(bus: AudioBus, node: SNode)(implicit tx: T): Synth = {
    val sg = SynthGraph {
      import de.sciss.synth.Ops.stringToControl
      import de.sciss.synth._
      import de.sciss.synth.ugen._
      val numIn     = bus.numChannels
      main.config.soloChannels.foreach { outChannels =>
        val numOut    = outChannels.size
        // println(s"numIn = $numIn, numOut = $numOut")
        val in        = In.ar("in".kr, numIn)
        val amp       = "amp".kr(1f)
        val sigOut    = SplayAz.ar(numOut, in)
        val mix       = sigOut * amp
        outChannels.zipWithIndex.foreach { case (sigOut, idx) =>
          val lim = Limiter.ar(sigOut, (-0.2).dbAmp)
          ReplaceOut.ar(lim, mix out idx)
        }
      }
    }
    // adding to the tail of root-node means we are guaranteed
    // behind the main line-outputs, which also use `ReplaceOut`.
    // this is allows one to us a line-output for non-solo headphones
    // and still override it with solo signals.
//    val target = node.server.defaultGroup
    val target = node.server.rootNode

    val soloSynth = Synth.play(sg, Some("solo"))(target = target, addAction = addToTail,
      args = "amp" -> soloVolume()(tx.peer) :: Nil, dependencies = node :: Nil)
    soloSynth.read(bus -> "in")
    _soloSynth.swap(Some(soloSynth))(tx.peer).foreach(_.dispose())
    soloSynth
  }

  final def mkValueMeter(bus: AudioBus, node: SNode)(fun: Vec[Double] => Unit)(implicit tx: T): Synth = {
    // return mkPeakMeter(bus, node)(d => fun(Vector(d)))

    val numCh = bus.numChannels
    val Name  = "/snap" // "/reply"
    val graph = valueMeterGraphMap.getOrElse(numCh, {
      val res = SynthGraph {
        import de.sciss.synth._
        import Ops._
        import ugen._
        val meterTr = Impulse.kr(1000.0 / LAYOUT_TIME)
        val busGE   = "in".kr
        val sig     = In.ar(busGE, numCh)
        // val sig     = InFeedback.ar(busGE, numCh)
        // sig  .poll(1, "signal")
        // busGE.poll(1, "bus"   )
        val values  = A2K.kr(sig)
        SendReply.kr(trig = meterTr, values = values, msgName = Name)
      }
      valueMeterGraphMap += numCh -> res
      res
    })
    val server  = node.server
    val syn     = Synth.play(graph, Some("snap"))(server.defaultGroup, addAction = addToTail,
      dependencies = node :: Nil)
    syn.read(bus -> "in")
    val NodeId = syn.peer.id
    val trigResp = message.Responder.add(server.peer) {
      case osc.Message(Name, NodeId, 0, raw @ _*) =>
        val vec: Vec[Double] = raw match {
          case rawV: Vec[_] => rawV         .map(_.asInstanceOf[Float].toDouble)
          case _            => raw.iterator .map(_.asInstanceOf[Float].toDouble).toIndexedSeq
        }
        defer(fun(vec))
    }
    // Responder.add is non-transactional. Thus, if the transaction fails, we need to remove it.
    scala.concurrent.stm.Txn.afterRollback { _ =>
      trigResp.remove()
    } (tx.peer)
    syn.onEnd(trigResp.remove())
    syn
  }

  protected def mkMonitor(bus: AudioBus, node: SNode)(fun: Vec[Double] => Unit)(implicit tx: T): Synth = {
    val numCh  = bus.numChannels
    val graph  = monitorGraphMap.getOrElse(numCh, {
      val res = SynthGraph {
        import de.sciss.synth._
        import Ops._
        import ugen._
        val meterTr = Impulse.kr(1000.0 / LAYOUT_TIME)
        val sig     = In.ar("in".kr, numCh)
        SendReply.kr(meterTr, sig)
      }
      monitorGraphMap += numCh -> res
      res
    })
    // de.sciss.synth.Server.default.dumpOSC()
    val syn = Synth.play(graph, Some("monitor"))(node.server.defaultGroup, addAction = addToTail,
      dependencies = node :: Nil)
    syn.read(bus -> "in")
    val NodeId = syn.peer.id
    val trigResp = message.Responder.add(node.server.peer) {
      case osc.Message("/reply", NodeId, 0, raw @ _*) =>
        val vec: Vec[Double] = raw match {
          case rawV: Vec[_] => rawV         .map(_.asInstanceOf[Float].toDouble)
          case _            => raw.iterator .map(_.asInstanceOf[Float].toDouble).toIndexedSeq
        }
        defer(fun(vec))
    }
    // Responder.add is non-transactional. Thus, if the transaction fails, we need to remove it.
    scala.concurrent.stm.Txn.afterRollback { _ =>
      trigResp.remove()
    } (tx.peer)
    syn.onEnd(trigResp.remove())
    syn
  }

  protected def disposeSoloSynth()(implicit tx: T): Unit = {
    _soloSynth.swap(None)(tx.peer).foreach(_.dispose())
  }

  def setSolo(vp: NuagesObj[T], onOff: Boolean)(implicit tx: T): Unit = {
    val oldObj = soloObj.swap(Some(vp))(tx.peer)
    oldObj.foreach(_.setSolo(onOff = false))
    vp.setSolo(onOff = onOff)
  }

  def mainSynth(implicit tx: RT): Option[Synth] = _mainSynth.get(tx.peer)
  def mainSynth_=(value: Option[Synth])(implicit tx: RT): Unit =
    _mainSynth.set(value)(tx.peer)

  def setMainVolume(v: Double)(implicit tx: T): Unit =
    _mainSynth.get(tx.peer).foreach(_.set("amp" -> v))

  def setSoloVolume(v: Double)(implicit tx: T): Unit = {
    implicit val itx: InTxn = tx.peer
    val oldV = soloVolume.swap(v)
    if (v == oldV) return
    _soloSynth().foreach(_.set("amp" -> v))
  }
}