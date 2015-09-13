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

import de.sciss.lucre.expr.{DoubleObj, DoubleVector, StringObj}
import de.sciss.lucre.stm
import de.sciss.lucre.stm.Obj
import de.sciss.synth.proc.graph.{Attribute, ScanIn, ScanInFix, ScanOut}
import de.sciss.synth.proc.{Folder, Proc}
import de.sciss.synth.{GE, Rate, SynthGraph, audio, control, proc, scalar}

import scala.concurrent.stm.TxnLocal

object DSL {
//  /** Magnet pattern for mkPar */
//  sealed trait ParDefault extends Any {
//    def mkObj[S <: Sys[S]](spec: ParamSpec)(implicit tx: S#Tx): Obj[S]
//  }
//  implicit final class ParScalar(val `this`: Double) extends AnyVal with ParDefault { me =>
//    import me.{`this` => default}
//    def mkObj[S <: Sys[S]](spec: ParamSpec)(implicit tx: S#Tx): Obj[S] = {
//      val defaultN  = spec.inverseMap(default)
//      DoubleObj.newVar(DoubleObj.newConst[S](defaultN))
//    }
//  }
//  implicit final class ParVector(val `this`: Vec[Double]) extends AnyVal with ParDefault { me =>
//    import me.{`this` => default}
//    def mkObj[S <: Sys[S]](spec: ParamSpec)(implicit tx: S#Tx): Obj[S] = {
//      val defaultN  = default.map(spec.inverseMap)
//      DoubleVector.newVar(DoubleVector.newConst[S](defaultN))
//    }
//  }
}
class DSL[S <: stm.Sys[S]] {
  // val imp = ExprImplicits[S]
  import proc.Implicits._

  private val current = TxnLocal[Proc[S]]()

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

  def pAudio(key: String, spec: ParamSpec, default: Attribute.Default)(implicit tx: S#Tx): GE =
    mkPar(audio, key = key, spec = spec, default = default)

  def pControl(key: String, spec: ParamSpec, default: Attribute.Default)(implicit tx: S#Tx): GE =
    mkPar(control, key = key, spec = spec, default = default)

  def pScalar(key: String, spec: ParamSpec, default: Attribute.Default)(implicit tx: S#Tx): GE =
    mkPar(scalar, key = key, spec = spec, default = default)

  def pAudioIn(key: String, numChannels: Int, spec: ParamSpec)(implicit tx: S#Tx): GE = {
    val obj       = current.get(tx.peer)
    val sig       = ScanInFix(key, numChannels)
    obj.inputs.add(key)
    spec.map(sig.clip(0, 1))
  }

  def shortcut(implicit tx: S#Tx): String = {
    val obj = current.get(tx.peer)
    obj.attr.$[StringObj](Nuages.KeyShortcut).map(_.value).getOrElse("")
  }
  def shortcut_=(value: String)(implicit tx: S#Tx): Unit = {
    val obj       = current.get(tx.peer)
    if (value.isEmpty) {
      obj.attr.remove(Nuages.KeyShortcut)
    } else {
      val paramObj = StringObj.newConst[S](value)
      obj.attr.put(Nuages.KeyShortcut, paramObj)
    }
  }

  private def mkPar(rate: Rate, key: String, spec: ParamSpec, default: Attribute.Default)(implicit tx: S#Tx): GE = {
    val obj       = current.get(tx.peer)
    val paramObj  = default match {
      case Attribute.Scalar(x) =>
        val defaultN  = spec.inverseMap(x)
        DoubleObj.newVar(DoubleObj.newConst[S](defaultN))
      case Attribute.Vector(xs) =>
        val defaultN  = xs.map(spec.inverseMap)
        DoubleVector.newVar(DoubleVector.newConst[S](defaultN))
    }
    val specObj   = ParamSpec.Obj.newConst[S](spec)
    obj.attr.put(key, paramObj)
    obj.attr.put(s"$key-${ParamSpec.Key}", specObj)
    val sig       = Attribute(rate, key, default)
    spec.map(sig.clip(0, 1))
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

  def generator(name: String)(fun: => GE)(implicit tx: S#Tx, n: Nuages[S]): Proc[S] = {
    val obj = mkProcObj(name) {
      val out = fun
      ScanOut(Proc.scanMainOut, out)
    }
    obj.outputs.add(Proc.scanMainOut)
    val genOpt = n.generators
    insertByName(genOpt.get, obj)
    obj
  }

  def filter(name: String)(fun: GE => GE)(implicit tx: S#Tx, n: Nuages[S]): Proc[S] = {
    val obj = mkProcObj(name) {
      val in  = ScanIn(Proc.scanMainIn)
      val out = fun(in)
      ScanOut(Proc.scanMainOut, out)
    }
    val proc  = obj
    proc.inputs .add(Proc.scanMainIn )
    proc.outputs.add(Proc.scanMainOut)
    insertByName(n.filters.get, obj)
    obj
  }

  def pAudioOut(key: String, sig: GE)(implicit tx: S#Tx): Unit = {
    val obj = current.get(tx.peer)
    ScanOut(key, sig)
    obj.outputs.add(key)
  }

  def sink(name: String)(fun: GE => Unit)(implicit tx: S#Tx, n: Nuages[S]): Proc[S] =
    sinkLike(n.filters.get, name, fun)

  def collector(name: String)(fun: GE => Unit)(implicit tx: S#Tx, n: Nuages[S]): Proc[S] =
    sinkLike(n.collectors.get, name, fun)

  private def sinkLike(folder: Folder[S], name: String, fun: GE => Unit)
                      (implicit tx: S#Tx, nuages: Nuages[S]): Proc[S] = {
    val obj = mkProcObj(name) {
      val in = ScanIn(Proc.scanMainIn)
      fun(in)
    }
    obj.inputs.add(Proc.scanMainIn)
    insertByName(folder, obj)
    obj
  }

  // def prepare(obj: Obj[S])(fun: S#Tx => Obj[S] => Unit): Unit = ...
}