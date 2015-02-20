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

import de.sciss.lucre.{event => evt}
import de.sciss.lucre.event.{EventLike, Sys}
import de.sciss.lucre.synth.InMemory
import de.sciss.serial.{DataOutput, DataInput, Serializer}
import de.sciss.synth.proc
import de.sciss.synth.proc.{Elem, FolderElem, Obj, Timeline, Folder}
import de.sciss.synth.proc.Implicits._

object NuagesImpl {
  def apply[S <: Sys[S]]()(implicit tx: S#Tx): Nuages[S] = {
    val tl        = Timeline[S]
    val timeline  = Obj(Timeline.Elem(tl))
    val folder    = Folder[S]
    folder.addLast(mkFolderObj(Nuages.NameGenerators))
    folder.addLast(mkFolderObj(Nuages.NameFilters   ))
    folder.addLast(mkFolderObj(Nuages.NameCollectors))
    folder.addLast(mkFolderObj(Nuages.NameMacros    ))
    val res       = new Impl(_folder = folder, timeline = timeline)
    res
  }

  private def mkFolderObj[S <: Sys[S]](name: String)(implicit tx: S#Tx): FolderElem.Obj[S] = {
    val f     = Folder[S]
    val obj   = Obj(FolderElem(f))
    obj.name  = name
    obj
  }

  def serializer[S <: Sys[S]]: Serializer[S#Tx, S#Acc, Nuages[S]] = anySer.asInstanceOf[Ser[S]]

  def read[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): Nuages[S] =
    serializer[S].read(in, access)

  private val anySer = new Ser[InMemory]

  private final class Ser[S <: Sys[S]] extends Serializer[S#Tx, S#Acc, Nuages[S]] {
    def write(n: Nuages[S], out: DataOutput): Unit = n.write(out)

    def read(in: DataInput, access: S#Acc)(implicit tx: S#Tx): Nuages[S] = {
      val cookie      = in.readInt()
      if (cookie != COOKIE) sys.error(s"Unexpected cookie (found $cookie, expected $COOKIE)")
      val folder      = Folder  .read[S](in, access)
      val timeline    = Obj.readT[S, Timeline.Elem](in, access)
      new Impl(_folder = folder, timeline = timeline)
    }
  }

  private final val COOKIE = 0x4E7501

  private final class Impl[S <: Sys[S]](_folder: Folder[S], val timeline: Timeline.Obj[S])
    extends Nuages[S] {

    def changed: EventLike[S, Nuages.Update[S]] = evt.Dummy[S, Nuages.Update[S]]

    def folder(implicit tx: S#Tx): Folder[S] = _folder

    def filters   (implicit tx: S#Tx): Option[Folder[S]] = findChild(Nuages.NameFilters   )
    def generators(implicit tx: S#Tx): Option[Folder[S]] = findChild(Nuages.NameGenerators)
    def collectors(implicit tx: S#Tx): Option[Folder[S]] = findChild(Nuages.NameCollectors)
    def macros    (implicit tx: S#Tx): Option[Folder[S]] = findChild(Nuages.NameMacros    )

    private def findChild(name: String)(implicit tx: S#Tx): Option[Folder[S]] = {
      val it = _folder.iterator.collect {
        case FolderElem.Obj(f) if f.name == name => f.elem.peer
      }
      if (it.hasNext) Some(it.next()) else None
    }

    def dispose()(implicit tx: S#Tx) = {
      _folder .dispose()
      timeline.dispose()
    }

    def write(out: DataOutput): Unit = {
      out.writeInt(COOKIE)
      _folder   .write(out)
      timeline  .write(out)
    }
  }

  // ---- Elem ----

  object ElemImpl extends proc.impl.ElemCompanionImpl[Nuages.Elem] {
    val typeID = Nuages.typeID

    def readIdentified[S <: Sys[S]](in: DataInput, access: S#Acc, targets: evt.Targets[S])
                                   (implicit tx: S#Tx): Nuages.Elem[S] with evt.Node[S] = {
      val peer = Nuages.read(in, access)
      new ElemImpl(targets, peer)
    }

    def readIdentifiedConstant[S <: Sys[S]](in: DataInput)(implicit tx: S#Tx): Nuages.Elem[S] =
      sys.error("Constant ProcGroup not supported")

    def apply[S <: Sys[S]](peer: Nuages[S])(implicit tx: S#Tx): Nuages.Elem[S] =
      new ElemImpl(evt.Targets[S], peer)
  }

  private final class ElemImpl[S <: Sys[S]](val targets: evt.Targets[S],
                                            val peer: Nuages[S])
    extends proc.impl.ActiveElemImpl[S] with Nuages.Elem[S] {

    def typeID = Nuages.typeID
    def prefix = "Nuages"

    // protected def peerEvent = peer.changed

    def mkCopy()(implicit tx: S#Tx): Nuages.Elem[S] = {
      val folderCopy  = peer.folder   // XXX TODO
      val tlCopy      = peer.timeline // XXX TODO .copyT(peer.timeline, peer.timeline.elem.peer)
      val copy        = new Impl(_folder = folderCopy, timeline = tlCopy)
      Nuages.Elem(copy)
    }
  }

  private lazy val _init: Unit = Elem.registerExtension(ElemImpl)
  def init(): Unit = _init
}
