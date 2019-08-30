/*
 *  Param.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2019 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.synth
package proc
package graph

import de.sciss.nuages.ParamSpec
import de.sciss.synth.UGenSource.Vec
import de.sciss.synth.proc.{UGenGraphBuilder => UGB}
import de.sciss.synth.ugen.ControlValues

object Param {
  def ar(key: String): Param = Param(audio, key = key, default = None, fixed = -1)
  def kr(key: String): Param = Param(audio, key = key, default = None, fixed = -1)

  def ar(key: String, default: ControlValues): Param = mk(audio, key = key, default = default)
  def kr(key: String, default: ControlValues): Param = mk(audio, key = key, default = default)

  private def mk(rate: Rate, key: String, default: ControlValues): Param = {
    val sz = default.seq.size
    val fixed = if (sz > 1) sz else -1    // XXX TODO -- is this always good?
    Param(rate, key = key, default = Some(default.seq), fixed = fixed)
  }
}

final case class Param(rate: Rate, key: String, default: Option[Vec[Float]], fixed: Int)
  extends GE.Lazy {

  protected def makeUGens: UGenInLike = {
    val b     = UGenGraphBuilder.get
    val sig   = Attribute(rate, key, default, fixed = fixed)
    val clip  = sig.max(0).min(1)   // some crazy bugs in Clip
    val spec  = b.requestInput(UGB.Input.Attribute(ParamSpec.composeKey(key))).peer match {
      case Some(spec: ParamSpec)  => spec
      case Some(other)            => sys.error(s"Cannot use $other as a param-spec")
      case None                   => ParamSpec()
    }
    spec.map(clip)
  }
}
