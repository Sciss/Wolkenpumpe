/*
 *  NuagesImpl.scala
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
package impl

import de.sciss.lucre.Event.Targets
import de.sciss.lucre.impl.{ObjFormat, SingleEventNode}
import de.sciss.lucre.{AnyTxn, Copy, Elem, Folder, Obj, Pull, Txn}
import de.sciss.nuages.Nuages.Surface
import de.sciss.serial.{DataInput, DataOutput, TFormat}
import de.sciss.synth.proc.Implicits._
import de.sciss.synth.proc.{Proc, Timeline}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.TxnLocal

object NuagesImpl {
  private[this] val nuagesLocal = TxnLocal[Nuages[_]]()

  def use[T <: Txn[T], A](n: Nuages[T])(body: => A)(implicit tx: T): A = {
    val old = nuagesLocal.swap(n)(tx.peer)
    try {
      body
    } finally {
      nuagesLocal.set(old)(tx.peer)
    }
  }

  def find[T <: Txn[T]]()(implicit tx: T): Option[Nuages[T]] = {
    Option(nuagesLocal.get(tx.peer).asInstanceOf[Nuages[T]])
  }

  private def mkCategFolder[T <: Txn[T]]()(implicit tx: T): Folder[T] = {
    val folder = Folder[T]()
    Nuages.CategoryNames.foreach { name =>
      folder.addLast(mkFolderObj(name))
    }
    folder
  }

  private def findChildIn[T <: Txn[T]](_folder: Folder[T], name: String)(implicit tx: T): Option[Folder[T]] = {
    // val foo = _folder.iterator.toList.map(_.name)
    val it = _folder.iterator.collect {
      case f: Folder[T] /* FolderElem.Obj(f) */ if f.name == name => f
    }
    if (it.hasNext) Some(it.next()) else None
  }

  def mkCategoryFolders[T <: Txn[T]](n: Nuages[T])(implicit tx: T): Unit = {
    val folder    = n.folder
    val toCreate  = Nuages.CategoryNames.filter { name =>
      findChildIn(folder, name).isEmpty
    }
    toCreate.foreach { name =>
      folder.addLast(mkFolderObj(name))
    }
  }

  def apply[T <: Txn[T]](surface: Surface[T])(implicit tx: T): Nuages[T] = {
    val targets   = Targets[T]()
    val folder    = mkCategFolder[T]()
    val res       = new Impl(targets, _folder = folder, surface = surface).connect()
    res
  }

  def folder[T <: Txn[T]](implicit tx: T): Nuages[T] = {
    val sPeer = Folder[T]()
    apply(Surface.Folder(sPeer))
  }

  def timeline[T <: Txn[T]](implicit tx: T): Nuages[T] = {
    val sPeer = Timeline[T]()
    apply(Surface.Timeline(sPeer))
  }

  def copyGraph[T <: Txn[T]](xs: Vec[Obj[T]])(implicit tx: T): Vec[Obj[T]] = {
    val inProcs = xs.collect {
      case proc: Proc[T] => proc
    }

    val inProcsS = inProcs.toSet
    val inOthers = xs diff inProcs

    val filter = inProcsS.contains _

    val copy  = Copy[T, T]()
    val res1  = inProcs.map { proc =>
      copy.putHint(proc, Proc.hintFilterLinks, filter)
      copy(proc)
    }
    val res2  = inOthers.map(copy(_))
    copy.finish()

    val res = res1 ++ res2
    res
  }

  private def mkFolderObj[T <: Txn[T]](name: String)(implicit tx: T): Folder[T] = {
    val f   = Folder[T]()
    f.name  = name
    // assert(f.name == name)
    f
  }

  def format[T <: Txn[T]]: TFormat[T, Nuages[T]] = anySer.asInstanceOf[Ser[T]]

  def read[T <: Txn[T]](in: DataInput)(implicit tx: T): Nuages[T] =
    format[T].readT(in)

  private val anySer = new Ser[AnyTxn]

  private final class Ser[T <: Txn[T]] extends ObjFormat[T, Nuages[T]] {
    protected def tpe: Obj.Type = Nuages
  }

  def readIdentifiedObj[T <: Txn[T]](in: DataInput)(implicit tx: T): Nuages[T] = {
    val targets     = Targets.read(in)
    val cookie      = in.readInt()
    if (cookie != COOKIE) sys.error(s"Unexpected cookie (found $cookie, expected $COOKIE)")
    val folder      = Folder  .read[T](in)
//    val timeline    = Timeline.read(in)
    val surface     = Surface.read[T](in)
    new Impl(targets, _folder = folder, surface = surface)
  }

  private final val COOKIE = 0x4E7501

  private final class Impl[T <: Txn[T]](protected val targets: Targets[T],
                                        _folder: Folder[T], val surface: Surface[T])
    extends Nuages[T] with SingleEventNode[T, Nuages.Update[T]] {

    def tpe: Obj.Type = Nuages

    def copy[Out <: Txn[Out]]()(implicit tx: T, txOut: Out, context: Copy[T, Out]): Elem[Out] = {
      val surfaceCopy = surface match {
        case s @ Surface.Timeline(peer) => s.copy(context(peer))
        case s @ Surface.Folder  (peer) => s.copy(context(peer: Folder[T]))
      }
      new Impl[Out](Targets[Out](), context(_folder), surfaceCopy).connect()
    }

    object changed extends Changed {
      def pullUpdate(pull: Pull[T])(implicit tx: T): Option[Nuages.Update[T]] = None
    }

    def folder(implicit tx: T): Folder[T] = _folder

    def filters   (implicit tx: T): Option[Folder[T]] = findChild(Nuages.NameFilters   )
    def generators(implicit tx: T): Option[Folder[T]] = findChild(Nuages.NameGenerators)
    def collectors(implicit tx: T): Option[Folder[T]] = findChild(Nuages.NameCollectors)
    def macros    (implicit tx: T): Option[Folder[T]] = findChild(Nuages.NameMacros    )

    private def findChild(name: String)(implicit tx: T): Option[Folder[T]] = findChildIn(_folder, name)

    private def disconnect(): Unit = ()

    def connect()(implicit tx: T): this.type = {
      // XXX TODO -- currently not listening to folder etc.
      this
    }

    protected def disposeData()(implicit tx: T): Unit = {
      disconnect()
      _folder .dispose()
      surface.dispose()
    }

    protected def writeData(out: DataOutput): Unit = {
      out.writeInt(COOKIE)
      _folder   .write(out)
      surface  .write(out)
    }
  }

//    def mkCopy()(implicit tx: T): Nuages.Elem[T] = {
//      val folderCopy  = peer.folder   // XXX TODO
//      val tlCopy      = peer.timeline // XXX TODO .copyT(peer.timeline, peer.timeline)
//      val copy        = new Impl(_folder = folderCopy, timeline = tlCopy)
//      Nuages.Elem(copy)
//    }

//  private lazy val _init: Unit = Elem.registerExtension(ElemImpl)
//  def init(): Unit = _init
}