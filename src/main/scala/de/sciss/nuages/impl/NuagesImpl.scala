/*
 *  NuagesImpl.scala
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
package impl

import de.sciss.lucre.event.Targets
import de.sciss.lucre.stm.impl.ObjSerializer
import de.sciss.lucre.stm.{Copy, Elem, NoSys, Obj, Sys}
import de.sciss.lucre.{event => evt}
import de.sciss.serial.{DataInput, DataOutput, Serializer}
import de.sciss.synth.proc.Implicits._
import de.sciss.synth.proc.{Folder, Proc, Timeline}

import scala.collection.immutable.{IndexedSeq => Vec}

object NuagesImpl {
  def apply[S <: Sys[S]]()(implicit tx: S#Tx): Nuages[S] = {
    val targets   = Targets[S]
    val tl        = Timeline[S]
    val folder    = Folder[S]
    folder.addLast(mkFolderObj(Nuages.NameGenerators))
    folder.addLast(mkFolderObj(Nuages.NameFilters   ))
    folder.addLast(mkFolderObj(Nuages.NameCollectors))
    folder.addLast(mkFolderObj(Nuages.NameMacros    ))
    val res       = new Impl(targets, _folder = folder, timeline = tl).connect()
    res
  }

  // `isScan` is true for scan-links and `false` for attribute mappings
  private final class LinkPreservation[S <: Sys[S]](val sink: Proc[S], val sinkKey  : String,
                                                    val source: Proc[S]  , val sourceKey: String,
                                                    val isScan: Boolean)

  def copyGraph[S <: Sys[S]](xs: Vec[Obj[S]])(implicit tx: S#Tx): Vec[Obj[S]] = {
    val inProcs = xs.collect {
      case proc: Proc[S] => proc
    }

    val inProcsS = inProcs.toSet
    val inOthers = xs diff inProcs

    val filter = inProcsS.contains _

    val copy  = Copy[S, S]
    val res1  = inProcs.map { proc =>
      copy.putHint(proc, Proc.hintFilterLinks, filter)
      copy(proc)
    }
    val res2  = inOthers.map(copy(_))
    copy.finish()

    val res = res1 ++ res2
    res
  }

  private def mkFolderObj[S <: Sys[S]](name: String)(implicit tx: S#Tx): Folder[S] = {
    val f   = Folder[S]
    f.name  = name
    // assert(f.name == name)
    f
  }

  def serializer[S <: Sys[S]]: Serializer[S#Tx, S#Acc, Nuages[S]] = anySer.asInstanceOf[Ser[S]]

  def read[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): Nuages[S] =
    serializer[S].read(in, access)

  private val anySer = new Ser[NoSys]

  private final class Ser[S <: Sys[S]] extends ObjSerializer[S, Nuages[S]] {
    protected def tpe: Obj.Type = Nuages
  }

  def readIdentifiedObj[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): Nuages[S] = {
    val targets     = Targets.read(in, access)
    val cookie      = in.readInt()
    if (cookie != COOKIE) sys.error(s"Unexpected cookie (found $cookie, expected $COOKIE)")
    val folder      = Folder  .read[S](in, access)
    val timeline    = Timeline.read(in, access)
    new Impl(targets, _folder = folder, timeline = timeline)
  }

  private final val COOKIE = 0x4E7501

  private final class Impl[S <: Sys[S]](protected val targets: Targets[S],
                                        _folder: Folder[S], val timeline: Timeline[S])
    extends Nuages[S] with evt.impl.SingleNode[S, Nuages.Update[S]] {

    def tpe: Obj.Type = Nuages

    def copy[Out <: Sys[Out]]()(implicit tx: S#Tx, txOut: Out#Tx, context: Copy[S, Out]): Elem[Out] =
      new Impl[Out](Targets[Out], context(_folder), context(timeline)).connect()

    object changed extends Changed {
      def pullUpdate(pull: evt.Pull[S])(implicit tx: S#Tx): Option[Nuages.Update[S]] = None
    }

    def folder(implicit tx: S#Tx): Folder[S] = _folder

    def filters   (implicit tx: S#Tx): Option[Folder[S]] = findChild(Nuages.NameFilters   )
    def generators(implicit tx: S#Tx): Option[Folder[S]] = findChild(Nuages.NameGenerators)
    def collectors(implicit tx: S#Tx): Option[Folder[S]] = findChild(Nuages.NameCollectors)
    def macros    (implicit tx: S#Tx): Option[Folder[S]] = findChild(Nuages.NameMacros    )

    private def findChild(name: String)(implicit tx: S#Tx): Option[Folder[S]] = {
      // val foo = _folder.iterator.toList.map(_.name)
      val it = _folder.iterator.collect {
        case f: Folder[S] /* FolderElem.Obj(f) */ if f.name == name => f
      }
      if (it.hasNext) Some(it.next()) else None
    }

    private[this] def disconnect()(implicit tx: S#Tx) = ()

    def connect()(implicit tx: S#Tx): this.type = {
      // XXX TODO -- currently not listening to folder etc.
      this
    }

    protected def disposeData()(implicit tx: S#Tx): Unit = {
      disconnect()
      _folder .dispose()
      timeline.dispose()
    }

    protected def writeData(out: DataOutput): Unit = {
      out.writeInt(COOKIE)
      _folder   .write(out)
      timeline  .write(out)
    }
  }

//    def mkCopy()(implicit tx: S#Tx): Nuages.Elem[S] = {
//      val folderCopy  = peer.folder   // XXX TODO
//      val tlCopy      = peer.timeline // XXX TODO .copyT(peer.timeline, peer.timeline)
//      val copy        = new Impl(_folder = folderCopy, timeline = tlCopy)
//      Nuages.Elem(copy)
//    }

//  private lazy val _init: Unit = Elem.registerExtension(ElemImpl)
//  def init(): Unit = _init
}
