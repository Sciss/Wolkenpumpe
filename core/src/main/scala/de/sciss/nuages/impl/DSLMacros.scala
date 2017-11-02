//package de.sciss.nuages
//package impl
//
//import de.sciss.lucre.stm.Sys
//import de.sciss.synth.{GE, SynthGraph}
//import de.sciss.synth.proc.graph.ScanOut
//import de.sciss.synth.proc.impl.Macros.mkSource
//import de.sciss.synth.proc.{Code, Proc, SynthGraphObj}
//import de.sciss.synth.proc.Implicits._
//
//import scala.reflect.macros.blackbox
//
//object DSLMacros {
//  def procWithSource[S <: Sys[S]](c: blackbox.Context)(name: c.Expr[String], body: c.Expr[Unit])(tx: c.Expr[S#Tx])
//                                 (implicit tt: c.WeakTypeTag[S]): c.Expr[Proc[S]] = {
//    import c.universe._
//
//    val source      = mkSource(c)("proc", body.tree)
//    val sourceExpr  = c.Expr[String](Literal(Constant(source)))
//    reify {
//      val dsl     = c.prefix.splice.asInstanceOf[DSL[S]]
//      implicit val txc = tx.splice // don't bloody annotate the type with `S#Tx`, it will break scalac
//      val p       = Proc[S]
//      p.name      = name.splice
//      dsl.current.set(p   )(txc.peer)
//      p.graph()   = SynthGraphObj.newConst[S](SynthGraph(body.splice))
//      dsl.current.set(null)(txc.peer)
//      val code    = Code.SynthGraph(sourceExpr.splice)
//      val codeObj = Code.Obj.newVar[S](Code.Obj.newConst[S](code))
//      p.attr.put(Proc.attrSource, codeObj)
//      p
//    }
//  }
//
////  val p   = Proc[S]
////  p.name  = name
////  current.set(p)(tx.peer)
////  p.graph() = SynthGraph { fun }
////  current.set(null)(tx.peer)
////  p
//
//  def generator[S <: Sys[S]](c: blackbox.Context)(name: c.Expr[String])(body: c.Expr[GE])
//                            (tx: c.Expr[S#Tx], n: c.Expr[Nuages[S]])(implicit tt: c.WeakTypeTag[S]): c.Expr[Proc[S]] = {
//    import c.universe._
//    val fullBody: c.Expr[Unit] = reify {
//      val out = {
//        body.splice
//      }
//      ScanOut(Proc.mainOut, out)
//    }
//    val pEx = procWithSource[S](c)(name, fullBody)(tx)
//    reify {
//      val dsl     = c.prefix.splice.asInstanceOf[DSL[S]]
//      implicit val txc = tx.splice // don't bloody annotate the type with `S#Tx`, it will break scalac
//      val obj = pEx.splice
//      obj.outputs.add(Proc.mainOut)
//      val nuages = n.splice
//      val genOpt = nuages.generators
//      dsl.insertByName(genOpt.get, obj)
//      obj
//    }
//  }
//}
