/*
 *  KeyControl.scala
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

import java.awt.event.{MouseEvent, KeyEvent}
import java.awt.geom.Point2D
import javax.swing.KeyStroke

import de.sciss.lucre.stm
import de.sciss.lucre.stm.{IdentifierMap, Disposable}
import de.sciss.lucre.synth.Sys
import de.sciss.nuages.NuagesPanel._
import de.sciss.synth.proc.{StringElem, Obj, Folder}
import prefuse.controls.{Control, ControlAdapter}
import prefuse.visual.{EdgeItem, VisualItem}

import scala.concurrent.stm.TMap

object KeyControl {
  def apply[S <: Sys[S]](main: NuagesPanel[S])(implicit tx: S#Tx): Control with Disposable[S#Tx] = {
    val res = new Impl(main)
    res.init()
    res
  }

  private abstract class Category[S <: Sys[S]] {
    protected def observer: Disposable[S#Tx]
    protected def idMap: IdentifierMap[S#ID, S#Tx, KeyStroke]

    private val keyMap = TMap.empty[KeyStroke, stm.Source[S#Tx, Obj[S]]]

    final def dispose()(implicit tx: S#Tx): Unit = observer.dispose()

    protected final def elemAdded(elem: Obj[S])(implicit tx: S#Tx): Unit =
      elem.attr[StringElem](Nuages.KeyShortcut).foreach { expr =>
        Option(KeyStroke.getKeyStroke(expr.value)).foreach { ks =>
          keyMap.put(ks, tx.newHandle(elem))(tx.peer)
        }
      }

    protected final def elemRemoved(elem: Obj[S])(implicit tx: S#Tx): Unit =
      idMap.get(elem.id).foreach { ks =>
        idMap.remove(elem.id)
        keyMap.remove(ks)(tx.peer)
      }

    final def get(ks: KeyStroke): Option[stm.Source[S#Tx, Obj[S]]] = keyMap.single.get(ks)
  }

  private class Impl[S <: Sys[S]](main: NuagesPanel[S]) extends ControlAdapter with Disposable[S#Tx] {
    private var filters: Category[S] = _

    private def mkCategory(f: Folder[S])(implicit tx: S#Tx): Category[S] = new Category[S] {
      protected val idMap = tx.newInMemoryIDMap[KeyStroke]

      protected val observer = f.changed.react { implicit tx => upd =>
        upd.changes.foreach {
          case Folder.Added  (_, elem) => elemAdded  (elem)
          case Folder.Removed(_, elem) => elemRemoved(elem)
          // XXX TODO:
          // case Folder.Element(elem, Obj.AttrAdded(Nuages.KeyShortcut, value)) =>
          case _ =>
        }
      }
      f.iterator.foreach(elemAdded)
    }

    def init()(implicit tx: S#Tx): Unit = {
      val n = main.nuages
      filters = mkCategory(n.filters)
    }

    override def mousePressed(e: MouseEvent): Unit = {
      // println("requestFocus")
      main.display.requestFocus()
    }

    def dispose()(implicit tx: S#Tx): Unit = {
      filters.dispose()
    }

    override def itemKeyPressed(vi: VisualItem, e: KeyEvent): Unit = {
      // println(s"itemKeyPressed '${e.getKeyChar}'")
      vi match {
        case ei: EdgeItem =>
          val ks = KeyStroke.getKeyStroke(e.getKeyCode, e.getModifiers)
          filters.get(ks).foreach { objH =>
            // println("found filter shortcut")
            val nSrc  = ei.getSourceItem
            val nTgt  = ei.getTargetItem
            val vis   = main.visualization
            val _ve   = vis.getVisualItem(NuagesPanel.GROUP_GRAPH, ei)
            (vis.getRenderer(nSrc), vis.getRenderer(nTgt), _ve) match {
              case (_: NuagesShapeRenderer[_], _: NuagesShapeRenderer[_], ve) =>
                val srcData = nSrc.get(COL_NUAGES).asInstanceOf[VisualData[S]]
                val tgtData = nTgt.get(COL_NUAGES).asInstanceOf[VisualData[S]]
                if (srcData != null && tgtData != null)
                  (srcData, tgtData) match {
                    case (vOut: VisualScan[S], vIn: VisualScan[S]) =>
                      val r  = ve.getBounds
                      val pt = new Point2D.Double(r.getCenterX, r.getCenterY)
                      // println("TODO: Insert Filter")
                      main.cursor.step { implicit tx =>
                        for {
                          pred <- vOut.scan
                          succ <- vIn .scan
                        } {
                          main.createFilter(pred = pred, succ = succ, source = objH(), pt = pt)
                        }
                      }
                    case _ =>
                  }

              case _ =>
            }
          }

        case _ =>
      }
    }

    override def keyPressed(e: KeyEvent): Unit = {
      // println(s"keyPressed '${e.getKeyChar}'")
    }
  }
}
