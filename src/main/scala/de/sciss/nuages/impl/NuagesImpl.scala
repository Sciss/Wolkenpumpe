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

import de.sciss.lucre.event.EventLike
import de.sciss.lucre.stm.{Obj, Sys}
import de.sciss.lucre.synth.InMemory
import de.sciss.lucre.{event => evt}
import de.sciss.serial.{DataInput, DataOutput, Serializer}
import de.sciss.synth.proc
import de.sciss.synth.proc.{Folder, Proc, Scan, Timeline}

import scala.collection.immutable.{IndexedSeq => Vec}

import proc.Implicits._

object NuagesImpl {
  private final val DEBUG = false

  def apply[S <: Sys[S]]()(implicit tx: S#Tx): Nuages[S] = {
    val tl        = Timeline[S]
    val folder    = Folder[S]
    folder.addLast(mkFolderObj(Nuages.NameGenerators))
    folder.addLast(mkFolderObj(Nuages.NameFilters   ))
    folder.addLast(mkFolderObj(Nuages.NameCollectors))
    folder.addLast(mkFolderObj(Nuages.NameMacros    ))
    val res       = new Impl(_folder = folder, timeline = tl)
    res
  }

  // `isScan` is true for scan-links and `false` for attribute mappings
  private final class LinkPreservation[S <: Sys[S]](val sink: Proc[S], val sinkKey  : String,
                                                    val source: Proc[S]  , val sourceKey: String,
                                                    val isScan: Boolean)

  def copyGraph[S <: Sys[S]](xs: Vec[Obj[S]])(implicit tx: S#Tx): Vec[Obj[S]] = {
    // we collect the proc copies and create a scan map
    // ; then in a second iteration, we must remove the
    // obsolete scan links and refresh them
    var procCopies  = Vector.empty[(Proc[S], Proc[S])] // (original, copy)
    var scanInMap   = Map.empty[Scan[S], (String, Proc[S])] // "old" scan to new proc
    var scanOutMap  = Map.empty[Scan[S], (String, Proc[S])] // "old" scan to new proc

    // simply copy all objects, and populate the scan-map on the way
    val res: Vec[Obj[S]] = xs.map {
      case procObj: Proc[S] =>
        val proc    = procObj
        val cpy     = ??? : Proc[S] // RRR Obj.copyT[S, Proc.Elem](procObj, cpyElem)
        procCopies :+= (procObj, cpy)
        scanInMap ++= proc.inputs.iterator.map { case (key, scan) =>
          if (DEBUG) println(s"scanInMap: add $scan.")
          scan -> (key, cpy)
        } .toMap
        scanOutMap ++= proc.outputs.iterator.map { case (key, scan) =>
          if (DEBUG) println(s"scanOutMap: add $scan.")
          scan -> (key, cpy)
        } .toMap

        cpy

      case other => ??? // RRR other.copy() Obj.copy(other)
    }

    // we'll now store the replacements of scan source links.
    // they carry the copied (new) sink, the key in the sink,
    // the copied (new) source as found through the scan-map,
    // and the source key. We can traverse the copied procs
    // because currently the `mkCopy` method of procs does
    // recreate scan links. If this "feature" disappears in the
    // future, we'll have to use the original procs.
    //
    // On the way, we'll disconnect all source links.
    //
    // A second type of link are mappings, where a scan
    // is stored in the attribute map of a sink. We'll also
    // have to replace these.
    //
    // Note that here the copied attribute map will also
    // have copied the scan already. This is wasteful and
    // should be avoid in the future. Now, we just need
    // to be careful to use the attribute map of the original
    // proc, otherwise we won't find the scan in the scan-map!

    var preserve = Vector.empty[LinkPreservation[S]]

    procCopies.foreach { case (origObj, cpyObj) =>
      val thisProc  = cpyObj
      val scanIns   = thisProc.inputs.iterator.toMap
      scanIns.foreach { case (thisKey, thisScan) =>
        val thisSources = thisScan.iterator.toList
        thisSources.foreach {
          case lnk @ Scan.Link.Scan(thatScan) =>
            scanOutMap.get(thatScan).foreach { case (thatKey, thatProc) =>
              preserve :+= new LinkPreservation(sink   = cpyObj  , sinkKey   = thisKey,
                                                source = thatProc, sourceKey = thatKey, isScan = true)
            }
            thisScan.remove(lnk)

          case _ =>
        }
      }
      val origAttr  = origObj.attr
      val cpyAttr   = cpyObj .attr
      val keys      = cpyAttr.keysIterator // .keys // ought to be identical to origAttr.keys
      keys.foreach { thisKey =>
        // important to use `origAttr` here, see note above
        origAttr.$[Scan](thisKey).foreach { thatScan =>
          if (DEBUG) println(s"In ${origObj.name}, attribute $thisKey points to a scan.")
          scanOutMap.get(thatScan: Scan[S]).fold {
            if (DEBUG) println(s".... did NOT find $thatScan.")
          } { case (thatKey, thatProc) =>
            if (DEBUG) println(".... we found that scan.")
            preserve :+= new LinkPreservation(sink   = cpyObj  , sinkKey   = thisKey,
                                              source = thatProc, sourceKey = thatKey, isScan = false)
          }
          cpyAttr.remove(thisKey)
        }
      }
    }

    // At this point, links have been bi-directionally removed
    // because `removeSource` is symmetric with `removeSink`. However,
    // there may be remaining sinks that point outside the selected
    // graph; we'll have to simply cut these as well.
    procCopies.foreach { case (_, cpyObj) =>
      val thisProc  = cpyObj
      val scans     = thisProc.outputs.iterator.toMap
      scans.foreach { case (thisKey, thisScan) =>
        val thisSinks = thisScan.iterator.toList
        thisSinks.foreach {
          case lnk @ Scan.Link.Scan(_) =>
            thisScan.remove(lnk)
          case _ =>
        }
      }
    }

    // Re-create correct links
    preserve.foreach { p =>
      val procObj     = p.sink
      val sourceScan  = p.source.outputs.add(p.sourceKey)
      if (p.isScan) {
        procObj.inputs.add(p.sinkKey).add(Scan.Link.Scan(sourceScan))
      } else {
        if (DEBUG) println(s"Re-assigning attribute scan entry for ${procObj.name} and key ${p.sinkKey}")
        procObj.attr.put(p.sinkKey, sourceScan) // XXX TODO - we'd lose attributes on the Scan.Obj
      }
    }

    res
  }

  private def mkFolderObj[S <: Sys[S]](name: String)(implicit tx: S#Tx): Folder[S] = {
    val f   = Folder[S]
    f.name  = name
    f
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
      val timeline    = Timeline.read(in, access) // Obj.readT[S, Timeline.Elem](in, access)
      new Impl(_folder = folder, timeline = timeline)
    }
  }

  private final val COOKIE = 0x4E7501

  private final class Impl[S <: Sys[S]](_folder: Folder[S], val timeline: Timeline[S])
    extends Nuages[S] {

    def changed: EventLike[S, Nuages.Update[S]] = evt.Dummy[S, Nuages.Update[S]]

    def folder(implicit tx: S#Tx): Folder[S] = _folder

    def filters   (implicit tx: S#Tx): Option[Folder[S]] = findChild(Nuages.NameFilters   )
    def generators(implicit tx: S#Tx): Option[Folder[S]] = findChild(Nuages.NameGenerators)
    def collectors(implicit tx: S#Tx): Option[Folder[S]] = findChild(Nuages.NameCollectors)
    def macros    (implicit tx: S#Tx): Option[Folder[S]] = findChild(Nuages.NameMacros    )

    private def findChild(name: String)(implicit tx: S#Tx): Option[Folder[S]] = {
      val it = _folder.iterator.collect {
        case f: Folder[S] /* FolderElem.Obj(f) */ if f.name == name => f
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

//    def mkCopy()(implicit tx: S#Tx): Nuages.Elem[S] = {
//      val folderCopy  = peer.folder   // XXX TODO
//      val tlCopy      = peer.timeline // XXX TODO .copyT(peer.timeline, peer.timeline)
//      val copy        = new Impl(_folder = folderCopy, timeline = tlCopy)
//      Nuages.Elem(copy)
//    }

//  private lazy val _init: Unit = Elem.registerExtension(ElemImpl)
//  def init(): Unit = _init
}
