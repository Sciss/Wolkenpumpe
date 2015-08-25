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

import java.awt.datatransfer.{Clipboard, ClipboardOwner, DataFlavor, Transferable, UnsupportedFlavorException}
import java.awt.event.{KeyEvent, MouseEvent}
import java.awt.geom.Point2D
import java.awt.{Color, Toolkit}
import javax.swing.KeyStroke
import javax.swing.event.{AncestorEvent, AncestorListener}

import de.sciss.desktop.KeyStrokes
import de.sciss.lucre.expr.StringObj
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Obj, Disposable, IdentifierMap}
import de.sciss.lucre.synth.Sys
import de.sciss.nuages.NuagesPanel._
import de.sciss.synth.proc.Folder
import prefuse.controls.{Control, ControlAdapter}
import prefuse.visual.{EdgeItem, NodeItem, VisualItem}

import scala.annotation.switch
import scala.concurrent.stm.TMap
import scala.swing.{Label, Orientation, Swing, TextField}
import scala.util.Try

object KeyControl {
  def apply[S <: Sys[S]](main: NuagesPanel[S])(implicit tx: S#Tx): Control with Disposable[S#Tx] = {
    val res = new Impl(main)
    res.init()
    res
  }

  private def internalFlavor[A](implicit ct: reflect.ClassTag[A]): DataFlavor =
    new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=\"" + ct.runtimeClass.getName + "\"")

  private final class ControlDrag(val value: Double, val spec: ParamSpec) extends Transferable {
    def getTransferDataFlavors: Array[DataFlavor] = Array(ControlFlavor)
    def isDataFlavorSupported(_flavor: DataFlavor): Boolean = _flavor == ControlFlavor

    def getTransferData(_flavor: DataFlavor): AnyRef  = {
      if (!isDataFlavorSupported(_flavor)) throw new UnsupportedFlavorException(_flavor)
      this
    }
  }

  private val ControlFlavor = internalFlavor[ControlDrag]

  private trait Category[S <: Sys[S]] extends Disposable[S#Tx] {
    def get(ks: KeyStroke): Option[stm.Source[S#Tx, Obj[S]]]
  }

  private abstract class CategoryImpl[S <: Sys[S]] extends Category[S] {
    protected def observer: Disposable[S#Tx]
    protected def idMap: IdentifierMap[S#ID, S#Tx, KeyStroke]

    private val keyMap = TMap.empty[KeyStroke, stm.Source[S#Tx, Obj[S]]]

    final def dispose()(implicit tx: S#Tx): Unit = observer.dispose()

    protected final def elemAdded(elem: Obj[S])(implicit tx: S#Tx): Unit =
      elem.attr.$[StringObj](Nuages.KeyShortcut).foreach { expr =>
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

  private final class Impl[S <: Sys[S]](main: NuagesPanel[S])
    extends ControlAdapter with Disposable[S#Tx] with ClipboardOwner {

    private[this] var filters: Category[S] = _
    private[this] val meta = KeyStrokes.menu1.mask

    private def mkEmptyCategory()(implicit tx: S#Tx): Category[S] = new Category[S] {
      def dispose()(implicit tx: S#Tx) = ()
      def get(ks: KeyStroke): Option[stm.Source[S#Tx, Obj[S]]] = None
    }

    private def mkCategory(f: Folder[S])(implicit tx: S#Tx): Category[S] = new CategoryImpl[S] {
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
      filters = n.filters.fold(mkEmptyCategory())(mkCategory)
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
                          main.insertFilter(pred = pred, succ = succ, flt = objH(), pt = pt)
                        }
                      }
                    case _ =>
                  }

              case _ =>
            }
          }

          case ni: NodeItem =>
            ni.get(COL_NUAGES) match {
              case vc: VisualControl[S] =>
                if ((e.getModifiers & meta) == meta) {
                  val clip = Toolkit.getDefaultToolkit.getSystemClipboard
                  if (e.getKeyCode == KeyEvent.VK_C) {        // copy
                    val data = new ControlDrag(vc.value, vc.spec)
                    clip.setContents(data, this)

                  } else if (e.getKeyCode == KeyEvent.VK_V) { // paste
                    if (clip.isDataFlavorAvailable(ControlFlavor)) {
                      val data = clip.getData(ControlFlavor).asInstanceOf[ControlDrag]
                      vc.setControl(data.value, instant = true) // XXX TODO -- which want to rescale
                    }
                  }
                } else {

                  if (e.getKeyCode == KeyEvent.VK_ENTER) showParamInput(vc)
                }

              case _ =>
            }

        case _ =>
      }
    }

    private def showParamInput(vc: VisualControl[S]): Unit = {
      val p = new OverlayPanel {
        val ggValue = new TextField(f"${vc.spec.map(vc.value)}%1.3f", 12)
        ggValue.peer.addAncestorListener(new AncestorListener {
          def ancestorRemoved(e: AncestorEvent): Unit = ()
          def ancestorMoved  (e: AncestorEvent): Unit = ()
          def ancestorAdded  (e: AncestorEvent): Unit = ggValue.requestFocus()
        })
        contents += new BasicPanel(Orientation.Horizontal) {
          contents += ggValue
          if (vc.spec.unit.nonEmpty) {
            contents += Swing.HStrut(4)
            contents += new Label(vc.spec.unit) {
              foreground = Color.white
            }
          }
        }
        onComplete {
          close()
          Try(ggValue.text.toDouble).toOption.foreach { newValue =>
            val v = vc.spec.inverseMap(vc.spec.clip(newValue))
            vc.setControl(v, instant = true)
          }
        }
      }
      main.showOverlayPanel(p)
    }

    override def itemKeyTyped(vi: VisualItem, e: KeyEvent): Unit = {
      vi match {
        case ni: NodeItem =>
          ni.get(COL_NUAGES) match {
            case vc: VisualControl[S] =>
              val v = (e.getKeyChar: @switch) match {
                case 'r'  => math.random
                case 'n'  => 0.0
                case 'x'  => 1.0
                case 'c'  => 0.5
                case '['  =>
                  val s = vc.spec
                  val v = vc.value
                  val vNew = if (s.warp == IntWarp) {
                    s.inverseMap(s.map(v) - 1)
                  } else v - 0.005
                  math.max(0.0, vNew)

                case ']'  =>
                  val s = vc.spec
                  val v = vc.value
                  val vNew = if (s.warp == IntWarp) {
                    s.inverseMap(s.map(v) + 1)
                  } else v + 0.005
                  math.min(1.0, vNew)

                case _    => -1.0
              }
              if (v >= 0) vc.setControl(v, instant = true)

            case _ =>
          }

        case _ =>
      }
    }

    def lostOwnership(clipboard: Clipboard, contents: Transferable): Unit = ()
  }
}