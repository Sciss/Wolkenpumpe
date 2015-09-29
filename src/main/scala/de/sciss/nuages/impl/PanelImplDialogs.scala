package de.sciss.nuages
package impl

import java.awt.Point
import java.awt.geom.Point2D
import javax.swing.event.{AncestorEvent, AncestorListener}

import de.sciss.lucre.stm
import de.sciss.lucre.stm.Obj
import de.sciss.lucre.swing.{ListView, requireEDT}
import de.sciss.lucre.synth.Sys
import de.sciss.synth.proc.{Scan, Folder, Proc}
import prefuse.Display

import scala.collection.immutable.{IndexedSeq => Vec}
import scala.swing.{Component, Swing}

trait PanelImplDialogs[S <: Sys[S]] {
  private var fltPred: VisualScan[S] = _
  private var fltSucc: VisualScan[S] = _
  private var overlay = Option.empty[Component]

  protected def listGen  : ListView[S, Obj[S], Unit]
  protected def listFlt1 : ListView[S, Obj[S], Unit]
  protected def listCol1 : ListView[S, Obj[S], Unit]
  protected def listFlt2 : ListView[S, Obj[S], Unit]
  protected def listCol2 : ListView[S, Obj[S], Unit]
  protected def listMacro: ListView[S, Obj[S], Unit]

  protected def display: Display

  protected def cursor: stm.Cursor[S]

  protected def component: Component

  protected def insertFilter(pred: Scan[S], succ: Scan[S], fltSrc: Obj[S], fltPt: Point2D)(implicit tx: S#Tx): Unit

  protected def createGenerator(genSrc: Obj[S], colSrcOpt: Option[Obj[S]], pt: Point2D)(implicit tx: S#Tx): Unit

  protected def appendFilter(pred: Scan[S], fltSrc: Obj[S], colSrcOpt: Option[Obj[S]], fltPt: Point2D)
                            (implicit tx: S#Tx): Unit

  protected def insertMacro(macroF: Folder[S], pt: Point2D)(implicit tx: S#Tx): Unit

  private lazy val createFilterInsertDialog: OverlayPanel = {
    val p = new OverlayPanel()
    p.contents += listFlt1.component
    p.contents += Swing.VStrut(4)
    p.onComplete {
      listFlt1.guiSelection match {
        case Vec(fltIdx) =>
          p.close()
          val displayPt = display.getAbsoluteCoordinate(p.location, null)
          cursor.step { implicit tx =>
            // val nuages = nuagesH()
            listFlt1.list.foreach { fltList =>
              fltList.get(fltIdx).foreach {
                case flt: Proc[S] =>
                  (fltPred.parent.obj, fltSucc.parent.obj) match {
                    case (pred: Proc[S], succ: Proc[S]) =>
                      for {
                        predScan <- pred.outputs.get(fltPred.key)
                        succScan <- succ.inputs .get(fltSucc.key)
                      } {
                        insertFilter(predScan, succScan, flt, displayPt)
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

  private lazy val createGenDialog: OverlayPanel = {
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
          val displayPt = display.getAbsoluteCoordinate(p.location, null)
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

  private def createFilterOnlyFromDialog(p: OverlayPanel)(objFun: S#Tx => Option[Obj[S]]): Unit = {
    p.close()
    val displayPt = display.getAbsoluteCoordinate(p.location, null)
    cursor.step { implicit tx =>
      objFun(tx).foreach {
        case flt: Proc[S] =>
          fltPred.parent.obj match {
            case pred: Proc[S] =>
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

  private lazy val createFilterAppendDialog: OverlayPanel= {
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
          val displayPt = display.getAbsoluteCoordinate(p.location, null)
          cursor.step { implicit tx =>
            listFlt2.list.foreach { fltList =>
              listCol2.list.foreach { colList =>
                fltList.get(fltIdx).foreach {
                  case flt: Proc[S] =>
                    colList.get(colIdx).foreach {
                      case col: Proc[S] =>
                        fltPred.parent.obj match {
                          case pred: Proc[S] =>
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

  private lazy val createInsertMacroDialog: OverlayPanel = {
    val p = new OverlayPanel()
    p.contents += listMacro.component
    p.contents += Swing.VStrut(4)
    p.onComplete {
      listMacro.guiSelection match {
        case Vec(macIdx) =>
          p.close()
          val displayPt = display.getAbsoluteCoordinate(p.location, null)
          cursor.step { implicit tx =>
            listMacro.list.foreach { macroList =>
              macroList.get(macIdx).foreach {
                case macroFObj: Folder[S] =>
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
    val c = component
    val dw = c.peer.getWidth  - pp.getWidth
    val dh = c.peer.getHeight - pp.getHeight
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
      def ancestorAdded(e: AncestorEvent) = ()

      def ancestorRemoved(e: AncestorEvent): Unit = {
        pp.removeAncestorListener(this)
        if (overlay == Some(p)) {
          overlay = None
          display.requestFocus()
        }
      }

      def ancestorMoved(e: AncestorEvent) = ()
    })
    overlay = Some(p)
    true
  }

  def isOverlayShowing: Boolean = overlay.isDefined

  def showCreateGenDialog(pt: Point): Boolean = {
    requireEDT()
    showOverlayPanel(createGenDialog, Some(pt))
  }

  def showInsertFilterDialog(pred: VisualScan[S], succ: VisualScan[S], pt: Point): Boolean = {
    requireEDT()
    fltPred = pred
    fltSucc = succ
    showOverlayPanel(createFilterInsertDialog, Some(pt))
  }

  def showInsertMacroDialog(): Boolean = {
    requireEDT()
    showOverlayPanel(createInsertMacroDialog)
  }

  def showAppendFilterDialog(pred: VisualScan[S], pt: Point): Boolean = {
    requireEDT()
    fltPred = pred
    showOverlayPanel(createFilterAppendDialog, Some(pt))
  }
}