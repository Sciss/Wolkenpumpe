/*
 *  Nuages.scala
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

import de.sciss.lucre.{event => evt}
import evt.Sys
import de.sciss.lucre.stm.Disposable
import de.sciss.serial.{DataInput, Serializer, Writable}
import de.sciss.synth.proc
import de.sciss.synth.proc.{Timeline, Folder}
import impl.{NuagesImpl => Impl}

import collection.immutable.{IndexedSeq => Vec}
import language.implicitConversions

object Nuages {
  def apply[S <: Sys[S]]()(implicit tx: S#Tx): Nuages[S] =
    Impl[S]

  //  def empty[S <: Sys[S]](implicit tx: S#Tx): Nuages[S] =
  //    apply(Folder[S], Folder[S], Folder[S], Obj(Timeline.Elem(Timeline[S])))

  implicit def serializer[S <: Sys[S]]: Serializer[S#Tx, S#Acc, Nuages[S]] = Impl.serializer[S]

  def read[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): Nuages[S] = Impl.read(in, access)

  // ---- config ----

  sealed trait ConfigLike {
    def masterChannels: Option[Vec[Int]]
    def soloChannels  : Option[Vec[Int]]
    def recordPath    : Option[String]

    def meters        : Boolean
    def collector     : Boolean
    def fullScreenKey : Boolean
  }

  object Config {
    def apply(): ConfigBuilder = new ConfigBuilderImpl

    implicit def build(b: ConfigBuilder): Config = b.build
  }
  trait Config extends ConfigLike

  object ConfigBuilder {
    def apply(config: Config): ConfigBuilder = {
      val b = Config()
      import config._
      b.masterChannels  = masterChannels
      b.soloChannels    = soloChannels
      b.recordPath      = recordPath
      b.meters          = meters
      b.collector       = collector
      b.fullScreenKey   = fullScreenKey
      b
    }
  }
  trait ConfigBuilder extends ConfigLike {
    var masterChannels: Option[Vec[Int]]
    var soloChannels  : Option[Vec[Int]]
    var recordPath    : Option[String]

    var meters        : Boolean
    var collector     : Boolean
    var fullScreenKey : Boolean

    def build: Config
  }

  private final class ConfigBuilderImpl extends ConfigBuilder {
    override def toString = s"Nuages.ConfigBuilder@{$hashCode().toHexString}"

    var masterChannels: Option[Vec[Int]] = None
    var soloChannels  : Option[Vec[Int]] = None
    var recordPath    : Option[String]   = None

    var meters        : Boolean = true
    var collector     : Boolean = false
    var fullScreenKey : Boolean = true

    def build: Config = ConfigImpl(
      masterChannels  = masterChannels,
      soloChannels    = soloChannels,
      recordPath      = recordPath,
      meters          = meters,
      collector       = collector,
      fullScreenKey   = fullScreenKey
    )
  }

  private final case class ConfigImpl(
    masterChannels: Option[Vec[Int]],
    soloChannels  : Option[Vec[Int]],
    recordPath    : Option[String],
    meters        : Boolean,
    collector     : Boolean,
    fullScreenKey : Boolean
  ) extends Config {
    override def productPrefix = "Nuages.Config"
  }

  /** Attribute key for placing a key short cut. The value
    * is supposed to be a `String` adhering to the format
    * expected by `KeyStroke.getKeyStroke`
    *
    * @see [[javax.swing.KeyStroke#getKeyStroke(String)]]
    */
  final val KeyShortcut = "nuages-shortcut"

  final val NameFilters     = "filters"
  final val NameGenerators  = "generators"
  final val NameCollectors  = "collectors"

  final val typeID = 0x1000A

  // ---- Elem ----

  implicit object Elem extends proc.Elem.Companion[Elem] {
    def typeID = Nuages.typeID

    def apply[S <: Sys[S]](peer: Nuages[S])(implicit tx: S#Tx): Nuages.Elem[S] = Impl.ElemImpl(peer)

    implicit def serializer[S <: Sys[S]]: Serializer[S#Tx, S#Acc, Nuages.Elem[S]] = Impl.ElemImpl.serializer[S]
  }
  trait Elem[S <: Sys[S]] extends proc.Elem[S] {
    type Peer       = Nuages[S]
    type PeerUpdate = Nuages.Update[S]
    type This       = Elem[S]
  }

  /** Convenient short-cut */

  object Obj {
    def unapply[S <: Sys[S]](obj: proc.Obj[S]): Option[Nuages.Obj[S]] =
      if (obj.elem.isInstanceOf[Nuages.Elem[S]]) Some(obj.asInstanceOf[Nuages.Obj[S]])
      else None
  }
  type Obj[S <: Sys[S]] = proc.Obj.T[S, Nuages.Elem]

  // ---- event ----

  trait Update[S <: Sys[S]]
}
trait Nuages[S <: Sys[S]] extends Writable with Disposable[S#Tx] with evt.Publisher[S, Nuages.Update[S]] {
  def folder(implicit tx: S#Tx): Folder[S]

  def generators(implicit tx: S#Tx): Option[Folder[S]]
  def filters   (implicit tx: S#Tx): Option[Folder[S]]
  def collectors(implicit tx: S#Tx): Option[Folder[S]]

  def timeline  : Timeline.Obj[S]
}
