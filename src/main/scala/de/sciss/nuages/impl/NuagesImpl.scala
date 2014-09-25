package de.sciss.nuages
package impl

import de.sciss.lucre.synth.{InMemory, Sys}
import de.sciss.serial.{DataOutput, DataInput, Serializer}
import de.sciss.synth.proc.Folder

object NuagesImpl {
  def apply[S <: Sys[S]](generators: Folder[S], filters: Folder[S], collectors: Folder[S])
                        (implicit tx: S#Tx): Nuages[S] = {
    val res = new Impl(generators = generators, filters = filters, collectors = collectors)
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
      val generators  = Folder.read[S](in, access)
      val filters     = Folder.read[S](in, access)
      val collectors  = Folder.read[S](in, access)
      new Impl(generators = generators, filters = filters, collectors = collectors)
    }
  }

  private final val COOKIE = 0x4E7500

  private final class Impl[S <: Sys[S]](val generators: Folder[S], val filters: Folder[S], val collectors: Folder[S])
    extends Nuages[S] {

    def dispose()(implicit tx: S#Tx) = ()

    def write(out: DataOutput): Unit = {
      out.writeInt(COOKIE)
      generators.write(out)
      filters   .write(out)
      collectors.write(out)
    }
  }
}
