/*
 *  NuagesImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2014 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.lucre.synth.{InMemory, Sys}
import de.sciss.serial.{DataOutput, DataInput, Serializer}
import de.sciss.synth.proc.{Obj, Timeline, Folder}

object NuagesImpl {
  def apply[S <: Sys[S]](generators: Folder[S], filters: Folder[S], collectors: Folder[S],
                         timeline: Timeline.Obj[S])
                        (implicit tx: S#Tx): Nuages[S] = {
    val res = new Impl(generators = generators, filters = filters, collectors = collectors, timeline = timeline)
    res
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
      val generators  = Folder  .read[S](in, access)
      val filters     = Folder  .read[S](in, access)
      val collectors  = Folder  .read[S](in, access)
      // val timeline    = Timeline.read[S](in, access)
      val timeline    = Obj.readT[S, Timeline.Elem](in, access)
      new Impl(generators = generators, filters = filters, collectors = collectors, timeline = timeline)
    }
  }

  private final val COOKIE = 0x4E7500

  private final class Impl[S <: Sys[S]](val generators: Folder[S], val filters: Folder[S],
                                        val collectors: Folder[S], val timeline: Timeline.Obj[S])
    extends Nuages[S] {

    def dispose()(implicit tx: S#Tx) = ()

    def write(out: DataOutput): Unit = {
      out.writeInt(COOKIE)
      generators.write(out)
      filters   .write(out)
      collectors.write(out)
      timeline  .write(out)
    }
  }
}
