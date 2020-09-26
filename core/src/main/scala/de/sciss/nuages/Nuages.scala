/*
 *  Nuages.scala
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

import de.sciss.lucre.{AnyTxn, Disposable, Obj, Publisher, Txn, Folder => LFolder}
import de.sciss.nuages.impl.{NuagesImpl => Impl}
import de.sciss.serial.{DataInput, DataOutput, TFormat, Writable, WritableFormat}
import de.sciss.synth.proc

import scala.annotation.switch
import scala.collection.immutable.{IndexedSeq => Vec}
import scala.language.implicitConversions

object Nuages extends Obj.Type {
  final val typeId = 0x1000A

  def folder  [T <: Txn[T]](implicit tx: T): Nuages[T] = Impl.folder  [T]
  def timeline[T <: Txn[T]](implicit tx: T): Nuages[T] = Impl.timeline[T]

  def apply[T <: Txn[T]](surface: Surface[T])(implicit tx: T): Nuages[T] = Impl[T](surface)

  implicit def format[T <: Txn[T]]: TFormat[T, Nuages[T]] = Impl.format[T]

  def read[T <: Txn[T]](in: DataInput)(implicit tx: T): Nuages[T] = Impl.read(in)

  /** Looks for all of `CategoryNames` and creates top-level folders of that name, if they do not exist yet. */
  def mkCategoryFolders[T <: Txn[T]](n: Nuages[T])(implicit tx: T): Unit = Impl.mkCategoryFolders(n)

  /** Find current instance, provided during particular
    * actions such as prepare (see `attrPrepare`) and dispose (see `attrDispose`).
    */
  def find[T <: Txn[T]]()(implicit tx: T): Option[Nuages[T]] = Impl.find()

  // ---- config ----

  override def readIdentifiedObj[T <: Txn[T]](in: DataInput)(implicit tx: T): Obj[T] =
    Impl.readIdentifiedObj(in)

  sealed trait ConfigLike {
    def mainChannels  : Option[Vec[Int]]
    def soloChannels  : Option[Vec[Int]]
    def recordPath    : Option[String]

    def lineInputs    : Vec[NamedBusConfig]
    def micInputs     : Vec[NamedBusConfig]
    def lineOutputs   : Vec[NamedBusConfig]

    def meters        : Boolean
    def collector     : Boolean
    def fullScreenKey : Boolean

    /** Whether to automatically start the transport when the window is opened */
    def autoStart     : Boolean
    /** Whether to install the default main synth */
    def mainSynth     : Boolean
    /** Whether to 'splay' all main channels when routing to line outputs and
      * channel numbers do not match.
      */
    def lineOutputsSplay: Boolean

    def showTransport: Boolean

    /** If opening a frame by default. `false` to hide UI */
    def showFrame: Boolean

    def displaySize: (Int, Int)
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
      b.mainChannels      = mainChannels
      b.soloChannels      = soloChannels
      b.recordPath        = recordPath

      b.lineInputs        = lineInputs
      b.micInputs         = micInputs
      b.lineOutputs       = lineOutputs

      b.meters            = meters
      b.collector         = collector
      b.fullScreenKey     = fullScreenKey

      b.autoStart         = autoStart
      b.mainSynth         = mainSynth
      b.lineOutputsSplay  = lineOutputsSplay
      b.showTransport     = showTransport
      b.showFrame         = showFrame
      b.displaySize       = displaySize
      b
    }
  }
  trait ConfigBuilder extends ConfigLike {
    var mainChannels  : Option[Vec[Int]]
    var soloChannels  : Option[Vec[Int]]
    var recordPath    : Option[String]

    var lineInputs    : Vec[NamedBusConfig]
    var micInputs     : Vec[NamedBusConfig]
    var lineOutputs   : Vec[NamedBusConfig]

    var meters        : Boolean
    var collector     : Boolean
    var fullScreenKey : Boolean

    var autoStart       : Boolean = true  // XXX TODO --- move impl to `Impl` in major version
    var mainSynth       : Boolean = true  // XXX TODO --- move impl to `Impl` in major version
    var lineOutputsSplay: Boolean = true  // XXX TODO --- move impl to `Impl` in major version
    var showTransport   : Boolean = true  // XXX TODO --- move impl to `Impl` in major version
    var showFrame       : Boolean = true  // XXX TODO --- move impl to `Impl` in major version

    var displaySize     : (Int, Int) = (960, 640)  // XXX TODO --- move impl to `Impl` in major version

    def build: Config
  }

  private final class ConfigBuilderImpl extends ConfigBuilder {
    override def toString = s"Nuages.ConfigBuilder@${hashCode().toHexString}"

    var mainChannels  : Option[Vec[Int]]    = None
    var soloChannels  : Option[Vec[Int]]    = None
    var recordPath    : Option[String]      = None

    var lineInputs    : Vec[NamedBusConfig] = Vector.empty
    var micInputs     : Vec[NamedBusConfig] = Vector.empty
    var lineOutputs   : Vec[NamedBusConfig] = Vector.empty

    var meters        : Boolean             = true
    var collector     : Boolean             = false
    var fullScreenKey : Boolean             = true

    def build: Config = ConfigImpl(
      mainChannels      = mainChannels,
      soloChannels      = soloChannels,
      recordPath        = recordPath,

      lineInputs        = lineInputs,
      micInputs         = micInputs,
      lineOutputs       = lineOutputs,

      meters            = meters,
      collector         = collector,
      fullScreenKey     = fullScreenKey,

      autoStart         = autoStart,
      mainSynth         = mainSynth,
      lineOutputsSplay  = lineOutputsSplay,
      showTransport     = showTransport,
      showFrame         = showFrame,
      displaySize       = displaySize,
    )
  }

  private final case class ConfigImpl(
                                       mainChannels     : Option[Vec[Int]],
                                       soloChannels     : Option[Vec[Int]],
                                       recordPath       : Option[String],

                                       lineInputs       : Vec[NamedBusConfig],
                                       micInputs        : Vec[NamedBusConfig],
                                       lineOutputs      : Vec[NamedBusConfig],

                                       meters           : Boolean,
                                       collector        : Boolean,
                                       fullScreenKey    : Boolean,

                                       autoStart        : Boolean,
                                       mainSynth        : Boolean,
                                       lineOutputsSplay : Boolean,
                                       showTransport    : Boolean,
                                       showFrame        : Boolean,
                                       displaySize      : (Int, Int),
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

  /** Attribute key for placing a preparatory `Action`
    * with a sound process. This is invoked when a new
    * process is created by the user within Wolkenpumpe.
    */
  final val attrPrepare     = "nuages-prepare"

  /** Attribute key for placing a clean-up `Action`
    * with a sound process. This is invoked when a
    * process is deleted by the user within Wolkenpumpe.
    */
  final val attrDispose     = "nuages-dispose"

  /** Convenience key for storing `ArtifactLocation`
    * of base directory for recording live snippets.
    */
  final val attrRecLoc      = "nuages-rec-loc"

  final val NameFilters     = "filters"
  final val NameGenerators  = "generators"
  final val NameCollectors  = "collectors"
  final val NameMacros      = "macros"

  final val CategoryNames: List[String] =
    List(Nuages.NameGenerators, Nuages.NameFilters, Nuages.NameCollectors, Nuages.NameMacros)

  // ---- event ----

  trait Update[T <: Txn[T]]

  // ---- functions ----

  def copyGraph[T <: Txn[T]](xs: Vec[Obj[T]])(implicit tx: T): Vec[Obj[T]] = Impl.copyGraph(xs)

  // ---- surface ----

  object Surface {
    case class Timeline[T <: Txn[T]](peer: proc.Timeline.Modifiable[T]) extends Surface[T] { def isTimeline = true  }
    case class Folder  [T <: Txn[T]](peer: LFolder                 [T]) extends Surface[T] { def isTimeline = false }

    implicit def format[T <: Txn[T]]: TFormat[T, Surface[T]] = anyFmt.asInstanceOf[Fmt[T]]

    def read[T <: Txn[T]](in: DataInput)(implicit tx: T): Surface[T] =
      (in.readByte(): @switch) match {
        case 0      => Surface.Folder  (LFolder.read(in))
        case 1      => Surface.Timeline(proc.Timeline.Modifiable.read(in))
        case other  => sys.error(s"Unexpected cookie $other")
      }

    private[this] val anyFmt = new Fmt[AnyTxn]

    private[this] final class Fmt[T <: Txn[T]] extends WritableFormat[T, Surface[T]] {
      def readT(in: DataInput)(implicit tx: T): Surface[T] = Surface.read(in)
    }
  }
  sealed trait Surface[T <: Txn[T]] extends Disposable[T] with Writable {
    def peer: Obj[T]
    def isTimeline: Boolean

    final def dispose()(implicit tx: T): Unit = peer.dispose()

    final def write(out: DataOutput): Unit = {
      out.writeByte(if (isTimeline) 1 else 0)
      peer.write(out)
    }
  }
}
trait Nuages[T <: Txn[T]] extends Obj[T] with Publisher[T, Nuages.Update[T]] {
  def folder(implicit tx: T): LFolder[T]

  def generators(implicit tx: T): Option[LFolder[T]]
  def filters   (implicit tx: T): Option[LFolder[T]]
  def collectors(implicit tx: T): Option[LFolder[T]]
  def macros    (implicit tx: T): Option[LFolder[T]]

  def surface: Nuages.Surface[T]
}
