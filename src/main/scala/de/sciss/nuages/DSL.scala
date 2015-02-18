/*
 *  DSL.scala
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

package de.sciss.nuages

import de.sciss.lucre.expr.{Double => DoubleEx, String => StringEx}
import de.sciss.lucre.synth.Sys
import de.sciss.synth.proc.Implicits._
import de.sciss.synth.proc.{ExprImplicits, Folder, DoubleElem, StringElem, Obj, Proc}
import de.sciss.synth.proc.graph.{Attribute, ScanIn, ScanInFix, ScanOut}
import de.sciss.synth.{GE, Rate, SynthGraph, audio, control, scalar}

import scala.concurrent.stm.TxnLocal

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

  def pAudioIn(key: String, numChannels: Int, spec: ParamSpec)(implicit tx: S#Tx): GE = {
    val obj       = current.get(tx.peer)
    val sig       = ScanInFix(key, numChannels)
    obj.elem.peer.scans.add(key)
    spec.map(sig.clip(0, 1))
  }

  def shortcut(implicit tx: S#Tx): String = {
    val obj = current.get(tx.peer)
    obj.attr.apply[StringElem](Nuages.KeyShortcut).map(_.value).getOrElse("")
  }
  def shortcut_=(value: String)(implicit tx: S#Tx): Unit = {
    val obj       = current.get(tx.peer)
    if (value.isEmpty) {
      obj.attr.remove(Nuages.KeyShortcut)
    } else {
      val paramObj = Obj(StringElem(StringEx.newConst[S](value)))
      obj.attr.put(Nuages.KeyShortcut, paramObj)
    }
  }

  private def mkPar(rate: Rate, key: String, spec: ParamSpec, default: Double)(implicit tx: S#Tx): GE = {
    val obj       = current.get(tx.peer)
    val defaultN  = spec.inverseMap(default)
    val paramObj  = Obj(DoubleElem(DoubleEx.newVar(DoubleEx.newConst[S](defaultN))))
    val specObj   = Obj(ParamSpec.Elem(ParamSpec.Expr.newConst[S](spec)))
    // paramObj.attr.put(ParamSpec.Key, specObj)
    obj     .attr.put(key, paramObj)
    obj     .attr.put(s"$key-${ParamSpec.Key}", specObj)
    val sig       = Attribute(rate, key, default)
    spec.map(sig.clip(0, 1))
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
      ScanOut(Proc.Obj.scanMainOut, out)
    }
    obj.elem.peer.scans.add(Proc.Obj.scanMainOut)
    insertByName(n.generators.get, obj)
    obj
  }

  def filter(name: String)(fun: GE => GE)(implicit tx: S#Tx, n: Nuages[S]): Proc.Obj[S] = {
    val obj = mkObj(name) {
      val in  = ScanIn(Proc.Obj.scanMainIn)
      val out = fun(in)
      ScanOut(Proc.Obj.scanMainOut, out)
    }
    val scans = obj.elem.peer.scans
    scans.add(Proc.Obj.scanMainIn )
    scans.add(Proc.Obj.scanMainOut)
    insertByName(n.filters.get, obj)
    obj
  }

  def pAudioOut(key: String, sig: GE)(implicit tx: S#Tx): Unit = {
    val obj = current.get(tx.peer)
    ScanOut(key, sig)
    obj.elem.peer.scans.add(key)
  }

  def sink(name: String)(fun: GE => Unit)(implicit tx: S#Tx, n: Nuages[S]): Proc.Obj[S] =
    sinkLike(n.filters.get, name, fun)

  def collector(name: String)(fun: GE => Unit)(implicit tx: S#Tx, n: Nuages[S]): Proc.Obj[S] =
    sinkLike(n.collectors.get, name, fun)

  private def sinkLike(folder: Folder[S], name: String, fun: GE => Unit)
                      (implicit tx: S#Tx, nuages: Nuages[S]): Proc.Obj[S] = {
    val obj = mkObj(name) {
      val in = ScanIn(Proc.Obj.scanMainIn)
      fun(in)
    }
    obj.elem.peer.scans.add(Proc.Obj.scanMainIn)
    insertByName(folder, obj)
    obj
  }

  // def prepare(obj: Obj[S])(fun: S#Tx => Obj[S] => Unit): Unit = ...
}