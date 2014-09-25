package de.sciss.nuages

import de.sciss.synth
import de.sciss.synth.GE

final case class ParamSpec(lo: Double = 0.0, hi: Double = 1.0, warp: Warp = LinWarp, step: Double = 0.0) {
  import synth._
  def range = hi - lo
  def ratio = hi / lo

  def clip(value: Double): Double = math.max(lo, math.min(hi, value))

  /** Maps a number from normalized range to spec.
    * Note: this does involve rounding
    * according to the spec's step parameter (unless step is zero).
    */
  def map(value: Double): Double = {
    val w = warp.map(this, value)
    if (step <= 0.0) w else w.roundTo(step)
  }

  def unmap(value: Double): Double = warp.unmap(this, value)

  /** Maps a graph element from normalized range to spec.
    * Note: this does involve rounding
    * according to the spec's step parameter (unless step is zero).
    */
  def map(value: GE): GE = {
    val w = warp.map(this, value)
    if (step <= 0.0) w else w.roundTo(step)
  }

  def unmap(value: GE): GE = warp.unmap(this, value)
}
trait Warp {
  /** From normalized range to spec.
    * Note: this does not involve rounding
    * according to the spec's step parameter!
    */
  def map(spec: ParamSpec, value: Double): Double

  /** From spec to normalized range */
  def unmap(spec: ParamSpec, value: Double): Double

  def map(spec: ParamSpec, value: GE): GE

  def unmap(spec: ParamSpec, value: GE): GE
}

object LinWarp extends Warp {
  import synth._
  def map  (spec: ParamSpec, value: Double): Double = value * spec.range + spec.lo

  def unmap(spec: ParamSpec, value: Double): Double = (value - spec.lo) / spec.range

  def map  (spec: ParamSpec, value: GE): GE = value * spec.range + spec.lo

  def unmap(spec: ParamSpec, value: GE): GE = (value - spec.lo) / spec.range
}

object ExpWarp extends Warp {
  import synth._
  def map  (spec: ParamSpec, value: Double): Double = spec.ratio.pow(value) * spec.lo
  def unmap(spec: ParamSpec, value: Double): Double = (value / spec.lo).log / spec.ratio.log

  def map(spec: ParamSpec, value: GE): GE = (spec.hi / spec.lo).pow(value) * spec.lo

  def unmap(spec: ParamSpec, value: GE): GE = (value / spec.lo).log / spec.ratio.log
}