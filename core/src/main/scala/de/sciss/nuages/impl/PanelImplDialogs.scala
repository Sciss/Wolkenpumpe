/*
 *  PanelImplDialogs.scala
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

import java.awt.Point
import java.awt.geom.Point2D

import de.sciss.lucre.{Folder, Obj}
import de.sciss.lucre.swing.ListView
import de.sciss.lucre.swing.LucreSwing.requireEDT
import de.sciss.lucre.synth.Txn
import de.sciss.proc.Proc
import javax.swing.event.{AncestorEvent, AncestorListener}

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.swing.{Component, Swing}

trait PanelImplDialogs[T <: Txn[T]] {
  _: NuagesPanel[T] =>

  private[this] var fltPred: NuagesOutput   [T] = _
  private[this] var fltSucc: NuagesAttribute[T] = _
  private[this] var overlay = Option.empty[Component]

  protected def listGen  : ListView[T, Obj[T], Unit]
  protected def listFlt1 : ListView[T, Obj[T], Unit]
  protected def listCol1 : ListView[T, Obj[T], Unit]
  protected def listFlt2 : ListView[T, Obj[T], Unit]
  protected def listCol2 : ListView[T, Obj[T], Unit]
  protected def listMacro: ListView[T, Obj[T], Unit]

  protected def insertMacro(macroF: Folder[T], pt: Point2D)(implicit tx: T): Unit

  private[this] lazy val createFilterInsertDialog: OverlayPanel = {
    val p = new OverlayPanel()
    p.contents += listFlt1.component
    p.contents += Swing.VStrut(4)
    p.onComplete {
      listFlt1.guiSelection match {
        case Vec(fltIdx) =>
          p.close()
          val displayPt = dialogPoint(p)
          cursor.step { implicit tx =>
            // val nuages = nuagesH()
            listFlt1.list.foreach { fltList =>
              fltList.get(fltIdx).foreach {
                case flt: Proc[T] =>
                  fltPred.parent.obj match {
                    case pred: Proc[T] =>
                      for {
                        predScan <- pred.outputs.get(fltPred.key)
                        // succScan <- succ.attr   .get(fltSucc.key)
                      } {
                        insertFilter(predScan, fltSucc, flt, displayPt)
                      }
                    case _ =>
                  }
                case _ =>
              }
            }
          }
      }
    }
  }

  private[this] lazy val createGenDialog: OverlayPanel = {
    val p = new OverlayPanel()
    p.contents += listGen.component
    p.contents += Swing.VStrut(4)
    p.contents += listCol1.component
    p.contents += Swing.VStrut(4)
    p.onComplete {
      listGen.guiSelection match {
        case Vec(genIdx) =>
          val colIdxOpt = listCol1.guiSelection.headOption
          p.close()
          val displayPt = dialogPoint(p)
          cursor.step { implicit tx =>
            // val nuages = nuagesH()
            for {
              genList <- listGen.list
              gen <- genList.get(genIdx) // nuages.generators.get(genIdx)
            } {
              // val colOpt = colIdxOpt.flatMap(nuages.collectors.get)
              val colOpt = for {
                colIdx  <- colIdxOpt
                colList <- listCol1.list
                col     <- colList.get(colIdx)
              } yield col

              createGenerator(gen, colOpt, displayPt)
            }
          }
        case _ =>
      }
    }
  }

  private def createFilterOnlyFromDialog(p: OverlayPanel)(objFun: T => Option[Obj[T]]): Unit = {
    p.close()
    val displayPt = dialogPoint(p)
    cursor.step { implicit tx =>
      objFun(tx).foreach {
        case flt: Proc[T] =>
          fltPred.parent.obj match {
            case pred: Proc[T] =>
              for {
                predScan <- pred.outputs.get(fltPred.key)
              } {
                appendFilter(predScan, flt, None, displayPt)
              }
            case _ =>
          }
        case _ =>
      }
    }
  }

  private[this] lazy val createFilterAppendDialog: OverlayPanel= {
    val p = new OverlayPanel()
    p.contents += listFlt2.component
    p.contents += Swing.VStrut(4)
    p.contents += listCol2.component
    p.contents += Swing.VStrut(4)
    p.onComplete {
      (listFlt2.guiSelection.headOption, listCol2.guiSelection.headOption) match {
        case (Some(fltIdx), None) =>
          createFilterOnlyFromDialog(p) { implicit tx =>
            for {
              fltList <- listFlt2.list
              flt     <- fltList.get(fltIdx)
            } yield flt
            // nuages.filters.get(fltIdx)
          }

        case (None, Some(colIdx)) =>
          createFilterOnlyFromDialog(p) { implicit tx =>
            for {
              colList <- listCol2.list
              col     <- colList.get(colIdx)
            } yield col
            // nuages.collectors.get(colIdx)
          }

        case (Some(fltIdx), Some(colIdx)) =>
          p.close()
          val displayPt = dialogPoint(p)
          cursor.step { implicit tx =>
            listFlt2.list.foreach { fltList =>
              listCol2.list.foreach { colList =>
                fltList.get(fltIdx).foreach {
                  case flt: Proc[T] =>
                    colList.get(colIdx).foreach {
                      case col: Proc[T] =>
                        fltPred.parent.obj match {
                          case pred: Proc[T] =>
                            for {
                              predScan <- pred.outputs.get(fltPred.key)
                            } {
                              appendFilter(predScan, flt, Some(col), displayPt)
                            }
                          case _ =>
                        }
                      case _ =>
                    }
                  case _ =>
                }
              }
            }
          }

        case _ =>
      }
    }
  }

  private def dialogPoint(p: OverlayPanel): Point2D = {
    val pt        = p.locationHint.getOrElse(p.location)
    val displayPt = display.getAbsoluteCoordinate(pt, null)
    displayPt
  }

  private[this] lazy val createInsertMacroDialog: OverlayPanel = {
    val p = new OverlayPanel()
    p.contents += listMacro.component
    p.contents += Swing.VStrut(4)
    p.onComplete {
      listMacro.guiSelection match {
        case Vec(macIdx) =>
          p.close()
          val displayPt = dialogPoint(p)
          cursor.step { implicit tx =>
            listMacro.list.foreach { macroList =>
              macroList.get(macIdx).foreach {
                case macroFObj: Folder[T] =>
                  insertMacro(macroFObj, displayPt)
                case _ =>
              }
            }
          }
        case _ =>
      }
    }
  }

  def showOverlayPanel(p: OverlayPanel, ptOpt: Option[Point] = None): Boolean = {
    if (overlay.isDefined) return false
    val pp = p.peer
    val c  = component
    val dw = c.peer.getWidth  - pp.getWidth
    val dh = c.peer.getHeight - pp.getHeight
    p.locationHint = ptOpt
    ptOpt.fold {
      pp.setLocation(math.max(0, dw/2), math.min(dh, dh/2))
    } { pt =>
      val x = math.max(0, math.min(pt.getX.toInt , dw))
      val y = math.min(math.max(0, pt.getY.toInt), dh) // make sure bottom is visible
      pp.setLocation(x, y)
    }
    //println( "aqui " + p.getX + ", " + p.getY + ", " + p.getWidth + ", " + p.getHeight )
    c.peer.add(pp, 0)
    c.revalidate()
    c.repaint()
    pp.addAncestorListener(new AncestorListener {
      def ancestorAdded(e: AncestorEvent): Unit = ()

      def ancestorRemoved(e: AncestorEvent): Unit = {
        pp.removeAncestorListener(this)
        if (overlay.contains(p)) {
          overlay = None
          display.requestFocus()
        }
      }

      def ancestorMoved(e: AncestorEvent): Unit = ()
    })
    overlay = Some(p)
    true
  }

  def isOverlayShowing: Boolean = overlay.isDefined

  def showCreateGenDialog(pt: Point): Boolean = {
    requireEDT()
    showOverlayPanel(createGenDialog, Some(pt))
  }

  def showInsertFilterDialog(pred: NuagesOutput[T], succ: NuagesAttribute[T], pt: Point): Boolean = {
    requireEDT()
    fltPred = pred
    fltSucc = succ
    showOverlayPanel(createFilterInsertDialog, Some(pt))
  }

  def showInsertMacroDialog(): Boolean = {
    requireEDT()
    showOverlayPanel(createInsertMacroDialog)
  }

  def showAppendFilterDialog(pred: NuagesOutput[T], pt: Point): Boolean = {
    requireEDT()
    fltPred = pred
    showOverlayPanel(createFilterAppendDialog, Some(pt))
  }
}