/*
 *  ProcessHelper.scala
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

object ProcessHelper {
  //  def whenGlideDone(p: Proc, ctrlName: String)(fun: ProcTxn => Unit)(implicit tx: ProcTxn): Unit = {
  //    if (p.control(ctrlName).cv.mapping.isEmpty) {
  //      fun(tx)
  //    } else {
  //      lazy val l: Proc.Listener = new Proc.Listener {
  //        def updated(u: Proc.Update): Unit = {
  //          if (u.controls.find(tup => (tup._1.name == ctrlName) && tup._2.mapping.isEmpty).isDefined) {
  //            ProcTxn.atomic { implicit tx =>
  //              p.removeListener(l)
  //              fun(tx)
  //            }
  //          }
  //        }
  //      }
  //      p.addListener(l)
  //    }
  //  }

  //  def whenFadeDone(p: Proc)(fun: ProcTxn => Unit)(implicit tx: ProcTxn): Unit = {
  //    if (!p.state.fading) {
  //      fun(tx)
  //    } else {
  //      lazy val l: Proc.Listener = new Proc.Listener {
  //        def updated(u: Proc.Update): Unit = {
  //          if (!u.state.fading) {
  //            ProcTxn.atomic { implicit tx =>
  //              p.removeListener(l)
  //              fun(tx)
  //            }
  //          }
  //        }
  //      }
  //      p.addListener(l)
  //    }
  //  }
}