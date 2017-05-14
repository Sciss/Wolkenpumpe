/*
 *  Nuages.scala
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

import de.sciss.lucre.stm.{Disposable, NoSys, Obj, Sys}
import de.sciss.lucre.{event => evt}
import de.sciss.nuages.impl.{NuagesImpl => Impl}
import de.sciss.serial.{Writable, DataOutput, DataInput, Serializer}
import de.sciss.synth.proc
import de.sciss.synth.proc.Folder

import scala.annotation.switch
import scala.collection.immutable.{IndexedSeq => Vec}
import scala.language.implicitConversions

object Nuages extends Obj.Type {
  final val typeID = 0x1000A

  def folder  [S <: Sys[S]](implicit tx: S#Tx): Nuages[S] = Impl.folder  [S]
  def timeline[S <: Sys[S]](implicit tx: S#Tx): Nuages[S] = Impl.timeline[S]

  def apply[S <: Sys[S]](surface: Surface[S])(implicit tx: S#Tx): Nuages[S] = Impl[S](surface)

  implicit def serializer[S <: Sys[S]]: Serializer[S#Tx, S#Acc, Nuages[S]] = Impl.serializer[S]

  def read[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): Nuages[S] = Impl.read(in, access)

  // ---- config ----

  override def readIdentifiedObj[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): Obj[S] =
    Impl.readIdentifiedObj(in, access)

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
    override def toString = s"Nuages.ConfigBuilder@${hashCode().toHexString}"

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
  final val attrShortcut    = "nuages-shortcut"

  final val NameFilters     = "filters"
  final val NameGenerators  = "generators"
  final val NameCollectors  = "collectors"
  final val NameMacros      = "macros"

  // ---- event ----

  trait Update[S <: Sys[S]]

  // ---- functions ----

  def copyGraph[S <: Sys[S]](xs: Vec[Obj[S]])(implicit tx: S#Tx): Vec[Obj[S]] = Impl.copyGraph(xs)

  // ---- surface ----

  object Surface {
    case class Timeline[S <: Sys[S]](peer: proc.Timeline.Modifiable[S]) extends Surface[S] { def isTimeline = true  }
    case class Folder  [S <: Sys[S]](peer: proc.Folder             [S]) extends Surface[S] { def isTimeline = false }

    implicit def serializer[S <: Sys[S]]: Serializer[S#Tx, S#Acc, Surface[S]] = anySer.asInstanceOf[Ser[S]]

    def read[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): Surface[S] =
      (in.readByte(): @switch) match {
        case 0      => Surface.Folder  (proc.Folder.read(in, access))
        case 1      => Surface.Timeline(proc.Timeline.Modifiable.read(in, access))
        case other  => sys.error(s"Unexpected cookie $other")
      }

    private[this] val anySer = new Ser[NoSys]

    private[this] final class Ser[S <: Sys[S]] extends Serializer[S#Tx, S#Acc, Surface[S]] {
      def read(in: DataInput, access: S#Acc)(implicit tx: S#Tx): Surface[S] = Surface.read(in, access)
      def write(v: Surface[S], out: DataOutput): Unit = v.write(out)
    }
  }
  sealed trait Surface[S <: Sys[S]] extends Disposable[S#Tx] with Writable {
    def peer: Obj[S]
    def isTimeline: Boolean

    final def dispose()(implicit tx: S#Tx): Unit = peer.dispose()

    final def write(out: DataOutput): Unit = {
      out.writeByte(if (isTimeline) 1 else 0)
      peer.write(out)
    }
  }
}
trait Nuages[S <: Sys[S]] extends Obj[S] with evt.Publisher[S, Nuages.Update[S]] {
  def folder(implicit tx: S#Tx): Folder[S]

  def generators(implicit tx: S#Tx): Option[Folder[S]]
  def filters   (implicit tx: S#Tx): Option[Folder[S]]
  def collectors(implicit tx: S#Tx): Option[Folder[S]]
  def macros    (implicit tx: S#Tx): Option[Folder[S]]

  def surface: Nuages.Surface[S]
}
