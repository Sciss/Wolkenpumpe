package de.sciss.nuages
package impl

import de.sciss.lucre.{event => evt}
import de.sciss.lucre.event.Sys
import de.sciss.serial.DataInput
import de.sciss.synth.proc
import de.sciss.synth.proc.Elem

object ParamSpecImpl {
  object ElemImpl extends proc.impl.ElemImpl.Companion[ParamSpec.Elem] {
    def typeID = ParamSpec.typeID

    Elem.registerExtension(this)

    def apply[S <: Sys[S]](peer: ParamSpec.Expr[S])(implicit tx: S#Tx): ParamSpec.Elem[S] = {
      val targets = evt.Targets[S]
      new Impl[S](targets, peer)
    }

    def read[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): ParamSpec.Elem[S] =
      serializer[S].read(in, access)

    // ---- Elem.Extension ----

    /** Read identified active element */
    def readIdentified[S <: Sys[S]](in: DataInput, access: S#Acc, targets: evt.Targets[S])
                                   (implicit tx: S#Tx): ParamSpec.Elem[S] with evt.Node[S] = {
      val peer = ParamSpec.Expr.read(in, access)
      new Impl[S](targets, peer)
    }

    /** Read identified constant element */
    def readIdentifiedConstant[S <: Sys[S]](in: DataInput)(implicit tx: S#Tx): ParamSpec.Elem[S] =
      sys.error("Constant ParamSpec not supported")

    // ---- implementation ----

    private final class Impl[S <: Sys[S]](protected val targets: evt.Targets[S],
                                          val peer: ParamSpec.Expr[S])
      extends ParamSpec.Elem[S]
      with proc.impl.ElemImpl.Active[S] {

      def typeID = ElemImpl.typeID
      def prefix = "ParamSpec"

      override def toString() = s"$prefix$id"

      def mkCopy()(implicit tx: S#Tx): ParamSpec.Elem[S] = ParamSpec.Elem(peer)
    }
  }
}
