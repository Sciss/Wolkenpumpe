/*
 *  DSL.scala
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

import de.sciss.lucre.expr.{DoubleObj, DoubleVector, StringObj}
import de.sciss.lucre.stm
import de.sciss.lucre.stm.Obj
import de.sciss.synth.proc.graph.{Param, ScanIn, ScanInFix, ScanOut}
import de.sciss.synth.proc.{Folder, Proc}
import de.sciss.synth.ugen.ControlValues
import de.sciss.synth.{GE, Rate, SynthGraph, audio, control, proc, scalar}

import scala.concurrent.stm.TxnLocal

object DSL {
  def apply[S <: stm.Sys[S]]: DSL[S] = new DSL[S]

  var useScanFixed = false
}
class DSL[S <: stm.Sys[S]] private() {
  // val imp = ExprImplicits[S]
  import proc.Implicits._

  private[nuages] val current = TxnLocal[Proc[S]]()

  /** Creates a `Proc.Obj` with a synth-graph whose function
    * is determined by the `fun` argument.
    *
    * @param name name to assign to the resulting object
    * @param fun  the function that creates the synth-graph
    * @return     an object whose `Proc` has a synth-graph with the
    *             content generated by `fun` and the supplied `name`
    */
  def mkProcObj(name: String)(fun: => Unit)(implicit tx: S#Tx /* , n: Nuages[S] */): Proc[S] = {
    val p   = Proc[S]
    p.name  = name
    current.set(p)(tx.peer)
    p.graph() = SynthGraph { fun }
    current.set(null)(tx.peer)
    p
  }

  def pAudio(key: String, spec: ParamSpec, default: ControlValues)(implicit tx: S#Tx): GE =
    mkPar(audio, key = key, spec = spec, default = default)

  def pControl(key: String, spec: ParamSpec, default: ControlValues)(implicit tx: S#Tx): GE =
    mkPar(control, key = key, spec = spec, default = default)

  def pScalar(key: String, spec: ParamSpec, default: ControlValues)(implicit tx: S#Tx): GE =
    mkPar(scalar, key = key, spec = spec, default = default)

  // SCAN
//  def pAudioIn(key: String, numChannels: Int, spec: ParamSpec)(implicit tx: S#Tx): GE = {
//    val obj       = current.get(tx.peer)
//    val sig       = ScanInFix(key, numChannels)
//    obj.inputs.add(key)
//    // val clip      = sig.clip(0, 1)
//    val clip      = sig.max(0).min(1)   // some crazy bugs in Clip
//    spec.map(clip)
//  }

  def shortcut(implicit tx: S#Tx): String = {
    val obj = current.get(tx.peer)
    obj.attr.$[StringObj](Nuages.attrShortcut).map(_.value).getOrElse("")
  }
  def shortcut_=(value: String)(implicit tx: S#Tx): Unit = {
    val obj       = current.get(tx.peer)
    if (value.isEmpty) {
      obj.attr.remove(Nuages.attrShortcut)
    } else {
      val paramObj = StringObj.newConst[S](value)
      obj.attr.put(Nuages.attrShortcut, paramObj)
    }
  }

  private def mkPar(rate: Rate, key: String, spec: ParamSpec, default: ControlValues)(implicit tx: S#Tx): GE = {
    val obj         = current.get(tx.peer)
    val defaultFwd  = default.seq
    val defaultInv  = defaultFwd.map(spec.inverseMap(_))
    val paramObj: Obj[S] = defaultInv match {
      case Seq(defaultN) => DoubleObj   .newVar(DoubleObj   .newConst[S](defaultN))
      case     defaultN  => DoubleVector.newVar(DoubleVector.newConst[S](defaultN))
    }
    val specObj = ParamSpec.Obj.newConst[S](spec)
    val specKey = ParamSpec.composeKey(key)
    val objAttr = obj.attr
//    paramObj.attr.put(ParamSpec.Key, specObj)
    objAttr.put(specKey , specObj )
    objAttr.put(key     , paramObj)
    // obj.attr.put(s"$key-${ParamSpec.Key}", specObj)
    val fixed = if (default.seq.size > 1) default.seq.size else -1    // XXX TODO -- is this always good?

//    val sig = Attribute(rate, key, Some(default.seq), fixed = fixed)
//    val clip = sig.max(0).min(1)   // some crazy bugs in Clip
//    spec.map(clip)

//    val defaultInvF = defaultInv.map(_.toFloat)
    Param(rate, key = key, default = Some(default.seq), fixed = fixed)
  }

  /** Inserts an element into a folder at the index
    * corresponding with an alphabetical ordering by name.
    * The index can only be correctly determined if
    * alphabetical sorting (by lower-case names) is obeyed.
    * If an existing element with the same name as the
    * element to insert is found, that existing element will
    * be replaced.
    *
    * @param folder the folder to insert the element info
    * @param elem   the element to add
    */
  def insertByName(folder: Folder[S], elem: Obj[S])(implicit tx: S#Tx): Unit = {
    val name  = elem.name
    val nameL = name.toLowerCase
    val idx0  = folder.iterator.toList.indexWhere(_.name.toLowerCase.compareTo(nameL) >= 0)
    // replace existing items
    if (idx0 >= 0 && folder.get(idx0).exists(_.name == name)) {
      folder.removeAt(idx0)
    }
    val idx   = if (idx0 >= 0) idx0 else folder.size
    folder.insert(idx, elem)
  }

//  def generator(name: String)(body: GE)(implicit tx: S#Tx, n: Nuages[S]): Proc[S] =
//    macro DSLMacros.generator[S]

  def generator(name: String)(fun: => GE)(implicit tx: S#Tx, n: Nuages[S]): Proc[S] = {
    val obj = mkProcObj(name) {
      val out = fun
      ScanOut(Proc.mainOut, out)
    }
    obj.outputs.add(Proc.mainOut)
    val genOpt = n.generators
    insertByName(genOpt.get, obj)
    obj
  }

  def filter(name: String, numChannels: Int = -1)(fun: GE => GE)(implicit tx: S#Tx, n: Nuages[S]): Proc[S] = {
    val obj = mkProcObj(name) {
      val in  = if (numChannels == -1) ScanIn() else ScanInFix(numChannels)
      val out = fun(in)
      ScanOut(Proc.mainOut, out)
    }
    val proc  = obj
    proc.outputs.add(Proc.mainOut)
    insertByName(n.filters.get, obj)
    obj
  }

  def pAudioOut(key: String, sig: GE)(implicit tx: S#Tx): Unit = {
    val obj = current.get(tx.peer)
    ScanOut(key, sig)
    obj.outputs.add(key)
  }

  def sink(name: String, numChannels: Int = -1)(fun: GE => Unit)(implicit tx: S#Tx, n: Nuages[S]): Proc[S] =
    sinkLike(n.filters.get, name = name, numChannels = numChannels, fun = fun)

  def collector(name: String, numChannels: Int = -1)(fun: GE => Unit)(implicit tx: S#Tx, n: Nuages[S]): Proc[S] =
    sinkLike(n.collectors.get, name = name, numChannels = numChannels, fun = fun)

  private[this] def sinkLike(folder: Folder[S], name: String, numChannels: Int, fun: GE => Unit)
                            (implicit tx: S#Tx): Proc[S] = {
    val obj = mkProcObj(name) {
      val in = if (numChannels == -1) ScanIn() else ScanInFix(numChannels)
      fun(in)
    }
    insertByName(folder, obj)
    obj
  }

  // def prepare(obj: Obj[S])(fun: S#Tx => Obj[S] => Unit): Unit = ...
}