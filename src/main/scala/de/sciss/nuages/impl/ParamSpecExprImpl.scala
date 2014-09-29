//package de.sciss.nuages
//package impl
//
//import de.sciss.lucre.event.{Serializer, Sys}
//import de.sciss.lucre.expr.Expr.Const
//import de.sciss.lucre.expr.Type.Extension1
//import de.sciss.serial.{DataOutput, DataInput}
//
//object ParamSpecExprImpl extends ExprLikeType[ParamSpec, ParamSpec.Expr] {
//  final val typeID = 21
//
//  private final val COOKIE = 0x505300 // "PS\0"
//
//  def readValue(in: DataInput): ParamSpec = {
//    val cookie = in.readInt()
//    if (cookie != COOKIE) sys.error(s"Unexpected cookie (found $cookie, expected $COOKIE)")
//
//    val lo    = in.readDouble()
//    val hi    = in.readDouble()
//    val warp  = Warp.read(in)
//    val step  = in.readDouble()
//    val unit  = in.readUTF()
//    ParamSpec(lo = lo, hi = hi, warp = warp, step = step, unit = unit)
//  }
//
//  def writeValue(value: ParamSpec, out: DataOutput): Unit = {
//    out.writeInt(COOKIE)
//    import value._
//    out.writeDouble(lo)
//    out.writeDouble(hi)
//    warp.write(out)
//    out.writeDouble(step)
//    out.writeUTF(unit)
//  }
//
//  def readConst[S <: Sys[S]](in: DataInput): Const[S, ParamSpec] = ???
//
//  def newConst[S <: Sys[S]](value: ParamSpec): Repr[S] with Const[S, ParamSpec] = ???
//
//  def registerExtension(ext: Extension1[Repr]): Unit = ???
//
//  def read[S <: Sys[S]](in: DataInput, access: S#Acc)(implicit tx: S#Tx): Repr[S] = ???
//
//  implicit def serializer[S <: Sys[S]]: Serializer[S, Repr[S]] = ???
//}
