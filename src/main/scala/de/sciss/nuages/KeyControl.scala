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
import java.awt.{Point, Color, Toolkit}
import javax.swing.KeyStroke
import javax.swing.event.{DocumentEvent, DocumentListener, AncestorEvent, AncestorListener}

import de.sciss.desktop.KeyStrokes
import de.sciss.lucre.expr.StringObj
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Obj, Disposable, IdentifierMap}
import de.sciss.lucre.synth.Sys
import de.sciss.nuages.NuagesPanel._
import de.sciss.numbers
import de.sciss.swingplus.ListView
import de.sciss.synth.proc.{ObjKeys, Folder}
import prefuse.controls.{Control, ControlAdapter}
import prefuse.visual.{EdgeItem, NodeItem, VisualItem}

import scala.annotation.switch
import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.TMap
import scala.swing.{ScrollPane, Label, Orientation, Swing, TextField}
import scala.util.Try

object KeyControl {
  def apply[S <: Sys[S]](main: NuagesPanel[S])(implicit tx: S#Tx): Control with Disposable[S#Tx] = {
    val res = new Impl(main)
    res.init()
    res
  }

  private def internalFlavor[A](implicit ct: reflect.ClassTag[A]): DataFlavor =
    new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=\"" + ct.runtimeClass.getName + "\"")

  private final class ControlDrag(val values: Vec[Double], val spec: ParamSpec) extends Transferable {
    def getTransferDataFlavors: Array[DataFlavor] = Array(ControlFlavor)
    def isDataFlavorSupported(_flavor: DataFlavor): Boolean = _flavor == ControlFlavor

    def getTransferData(_flavor: DataFlavor): AnyRef  = {
      if (!isDataFlavorSupported(_flavor)) throw new UnsupportedFlavorException(_flavor)
      this
    }
  }

  private val ControlFlavor = internalFlavor[ControlDrag]

  private trait Category[S <: Sys[S]] extends Disposable[S#Tx] {
    def get(ks  : KeyStroke): Option[stm.Source[S#Tx, Obj[S]]]
    def get(name: String   ): Option[stm.Source[S#Tx, Obj[S]]]

    def names: Iterator[String]
  }

  // private final val colrGreen = new Color(0x00, 0x80, 0x00)

  private abstract class CategoryImpl[S <: Sys[S]] extends Category[S] {
    protected def observer: Disposable[S#Tx]
    protected def idMap: IdentifierMap[S#ID, S#Tx, KeyStroke]

    private val keyMap  = TMap.empty[KeyStroke, stm.Source[S#Tx, Obj[S]]]
    private val nameMap = TMap.empty[String   , stm.Source[S#Tx, Obj[S]]]

    final def dispose()(implicit tx: S#Tx): Unit = observer.dispose()

    def names: Iterator[String] = nameMap.single.keysIterator

    protected final def elemAdded(elem: Obj[S])(implicit tx: S#Tx): Unit = {
      implicit val itx = tx.peer
      val attr    = elem.attr
      val source  = tx.newHandle(elem)  // eagerly because we expect `name` to be present
      attr.$[StringObj](Nuages.KeyShortcut).foreach { expr =>
        Option(KeyStroke.getKeyStroke(expr.value)).foreach { ks =>
          keyMap.put(ks, source)(tx.peer)
        }
      }
      attr.$[StringObj](ObjKeys.attrName).foreach { expr =>
        nameMap.put(expr.value, source)
      }
    }

    protected final def elemRemoved(elem: Obj[S])(implicit tx: S#Tx): Unit = {
      implicit val itx = tx.peer
      idMap.get(elem.id).foreach { ks =>
        idMap.remove(elem.id)
        keyMap.remove(ks)(tx.peer)
      }
      elem.attr.$[StringObj](ObjKeys.attrName).foreach { expr =>
        nameMap.remove(expr.value)
      }
    }

    final def get(ks  : KeyStroke): Option[stm.Source[S#Tx, Obj[S]]] = keyMap .single.get(ks  )
    final def get(name: String   ): Option[stm.Source[S#Tx, Obj[S]]] = nameMap.single.get(name)
  }

  private final class Impl[S <: Sys[S]](main: NuagesPanel[S])
    extends ControlAdapter with Disposable[S#Tx] with ClipboardOwner {

    private[this] var filters   : Category[S] = _
    private[this] var generators: Category[S] = _

    private[this] val lastPt = new Point
    private[this] val p2d    = new Point2D.Float // throw-away

    private[this] val meta = KeyStrokes.menu1.mask

    private[this] var lastVi    : VisualItem  = _
    private[this] var lastChar  : Char        = _
    private[this] var lastTyped : Long        = _

    override def mouseDragged(e: MouseEvent): Unit = lastPt.setLocation(e.getX, e.getY)
    override def mouseMoved  (e: MouseEvent): Unit = lastPt.setLocation(e.getX, e.getY)

    private def mkEmptyCategory()(implicit tx: S#Tx): Category[S] = new Category[S] {
      def dispose()(implicit tx: S#Tx) = ()

      def get(ks  : KeyStroke): Option[stm.Source[S#Tx, Obj[S]]] = None
      def get(name: String   ): Option[stm.Source[S#Tx, Obj[S]]] = None

      def names: Iterator[String] = Iterator.empty
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
      val n       = main.nuages
      filters     = n.filters   .fold(mkEmptyCategory())(mkCategory)
      generators  = n.generators.fold(mkEmptyCategory())(mkCategory)
    }

    override def mousePressed(e: MouseEvent): Unit = {
      // println("requestFocus")
      main.display.requestFocus()
    }

    def dispose()(implicit tx: S#Tx): Unit = {
      filters   .dispose()
      generators.dispose()
    }

    override def keyPressed(e: KeyEvent): Unit = {
      if (e.getKeyCode == KeyEvent.VK_ENTER) {
        showCategoryInput(generators) { implicit tx => (gen, pt) =>
          main.createGenerator(gen, None, pt)
        }
      } else {
        val ks = KeyStroke.getKeyStroke(e.getKeyCode, e.getModifiers)
        generators.get(ks).foreach { objH =>
          val display = main.display
          display.getAbsoluteCoordinate(lastPt, p2d)
          main.cursor.step { implicit tx =>
            main.createGenerator(objH(), colOpt = None, pt = p2d)
          }
        }
      }
    }

    override def itemKeyPressed(vi: VisualItem, e: KeyEvent): Unit = {
      // println(s"itemKeyPressed '${e.getKeyChar}'")
      vi match {
        case ei: EdgeItem =>
          def perform[A](fun: (VisualScan[S], VisualScan[S], Point2D) => A): Option[A] = {
            val nSrc  = ei.getSourceItem
            val nTgt  = ei.getTargetItem
            val vis   = main.visualization
            val _ve   = vis.getVisualItem(NuagesPanel.GROUP_GRAPH, ei)
            (vis.getRenderer(nSrc), vis.getRenderer(nTgt)) match {
              case (_: NuagesShapeRenderer[_], _: NuagesShapeRenderer[_]) =>
                val srcData = nSrc.get(COL_NUAGES).asInstanceOf[VisualData[S]]
                val tgtData = nTgt.get(COL_NUAGES).asInstanceOf[VisualData[S]]
                if (srcData == null || tgtData == null) None else
                  (srcData, tgtData) match {
                    case (vOut: VisualScan[S], vIn: VisualScan[S]) =>
                      val r = _ve.getBounds
                      p2d.setLocation(r.getCenterX, r.getCenterY)
                      // main.display.getTransform.transform(p2d, p2d)
                      Some(fun(vOut, vIn, p2d))
                    case _ => None
                  }

              case _ => None
            }
          }

          if (e.getKeyCode == KeyEvent.VK_ENTER) {
            perform { (vOut, vIn, pt) =>
              showCategoryInput(filters) { implicit tx => (obj, pt0) =>
                val pred = vOut.scan
                val succ = vIn .scan
                // main.display.getTransform.transform(pt0, p2d)
                main.insertFilter(pred = pred, succ = succ, flt = obj, pt = pt0)
              }
            }

          } else {
            val ks = KeyStroke.getKeyStroke(e.getKeyCode, e.getModifiers)
            filters.get(ks).foreach { objH =>
              perform { (vOut, vIn, pt) =>
                main.cursor.step { implicit tx =>
                  val pred = vOut.scan
                  val succ = vIn .scan
                  main.insertFilter(pred = pred, succ = succ, flt = objH(), pt = pt)
                }
              }
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
                    vc.setControl(data.values, instant = true) // XXX TODO -- which want to rescale
                  }
                }
              } else {

                if (e.getKeyCode == KeyEvent.VK_ENTER) showParamInput(vc)
              }

            case vs: VisualScan[S] if vs.name == "out" =>
              def perform[A](fun: Point2D => A): A = {
                val vis   = main.visualization
                val _ve   = vis.getVisualItem(NuagesPanel.GROUP_GRAPH, ni)
                val r     = _ve.getBounds
                p2d.setLocation(r.getCenterX, r.getCenterY)
                fun(p2d)
              }

              if (e.getKeyCode == KeyEvent.VK_ENTER) {
                perform { pt =>
                  showCategoryInput(filters) { implicit tx => (obj, pt0) =>
                    main.appendFilter(pred = vs.scan, flt = obj, colOpt = None, pt = pt0)
                  }
                }

              } else {
                val ks = KeyStroke.getKeyStroke(e.getKeyCode, e.getModifiers)
                filters.get(ks).foreach { objH =>
                  perform { pt =>
                    main.cursor.step { implicit tx =>
                      main.appendFilter(pred = vs.scan, flt = objH(), colOpt = None, pt = pt)
                    }
                  }
                }
              }

            case _ =>
          }

        case _ =>
      }
    }

    // private val mCategList = ListView.Model.empty[String]

    private def showCategoryInput(c: Category[S])(complete: S#Tx => (Obj[S], Point2D) => Unit): Unit = {
      val p = new OverlayPanel { panel =>
        val ggName = new TextField(12)
        ggName.background = Color.black
        ggName.foreground = Color.white
        ggName.peer.addAncestorListener(new AncestorListener {
          def ancestorRemoved(e: AncestorEvent): Unit = ()
          def ancestorMoved  (e: AncestorEvent): Unit = ()
          def ancestorAdded  (e: AncestorEvent): Unit = ggName.requestFocus()
        })

        var candidates = Vec.empty[String]

        val ggCandidates              = new ListView(ListView.Model.wrap(candidates))
        ggCandidates.background       = Color.black
        ggCandidates.foreground       = Color.white
        ggCandidates.visibleRowCount  = 3
        ggCandidates.prototypeCellValue = "Gagaism one two"

        def updateFilter(): Unit = {
          val current = ggName.text
          candidates  = c.names.filter(_.contains(current)).toIndexedSeq.sorted
//          ggName.background =
//            if      (candidates.isEmpty  ) Color.red
//            else if (candidates.size == 1) colrGreen
//            else                           Color.black
          ggCandidates.model = ListView.Model.wrap(candidates)
          if (candidates.nonEmpty) ggCandidates.selectIndices(0)
        }

        ggName.peer.getDocument.addDocumentListener(new DocumentListener {
          def insertUpdate (e: DocumentEvent): Unit = updateFilter()
          def removeUpdate (e: DocumentEvent): Unit = updateFilter()
          def changedUpdate(e: DocumentEvent): Unit = ()
        })

        // mCategList.clear()
        contents += new BasicPanel(Orientation.Vertical) {
          contents += ggName
          contents += new ScrollPane(ggCandidates) // new ListView(mCategList)
        }
        onComplete {
          val sel = ggCandidates.selection.items
          close()
          if (sel.size == 1) {
            val name = sel.head
            c.get(name).foreach { source =>
              val b         = panel.bounds
              p2d.setLocation(b.getCenterX, b.getCenterY)
              val displayPt = main.display.getAbsoluteCoordinate(p2d, null)
              main.cursor.step { implicit tx =>
                complete(tx)(source(), displayPt)
              }
            }
          }
        }
      }

      val dim = p.preferredSize
      val pt  = new Point(lastPt.x - dim.width/2, lastPt.y - 12)

      main.showOverlayPanel(p, Some(pt))
    }

    private def showParamInput(vc: VisualControl[S]): Unit = {
      val p = new OverlayPanel {
        val ggValue = new TextField(f"${vc.spec.map(vc.value.head)}%1.3f", 12)
        ggValue.background = Color.black
        ggValue.foreground = Color.white
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
            val v   = vc.spec.inverseMap(vc.spec.clip(newValue))
            val vs  = Vector.fill(vc.numChannels)(v)
            vc.setControl(vs, instant = true)
          }
        }
      }
      main.showOverlayPanel(p, Some(calcPanelPoint(p, vc)))
    }

    private def calcPanelPoint(p: OverlayPanel, vc: VisualControl[S]): Point = {
      val vis   = main.visualization
      val _ve   = vis.getVisualItem(NuagesPanel.GROUP_GRAPH, vc.pNode)
      val b     = _ve.getBounds
      val dim   = p.preferredSize
      main.display.getTransform.transform(new Point2D.Double(b.getCenterX , b.getMaxY), p2d)
      new Point(p2d.getX.toInt - dim.width/2, p2d.getY.toInt - 12)
    }

    override def itemKeyTyped(vi: VisualItem, e: KeyEvent): Unit = {
      vi match {
        case ni: NodeItem =>
          ni.get(COL_NUAGES) match {
            case vc: VisualControl[S] =>
              val thisChar  = e.getKeyChar
              val thisTyped = System.currentTimeMillis()

              // for 'amp' and 'gain', key must be repeated twice
              def checkDouble(out: Vec[Double]): Vec[Double] = {
                val ok = (vc.name != "amp" && vc.name != "gain") ||
                  ((lastVi == vi && lastChar == thisChar) && thisTyped - lastTyped < 500)
                if (ok) out else Vector.empty
              }

              val v = (thisChar: @switch) match {
                case 'r'  => val v = math.random; checkDouble(Vector.fill(vc.numChannels)(v))
                case 'R'  => checkDouble(Vector.fill(vc.numChannels)(math.random))
                case 'n'  => Vector.fill(vc.numChannels)(0.0)
                case 'x'  => checkDouble(Vector.fill(vc.numChannels)(1.0))
                case 'c'  => Vector.fill(vc.numChannels)(0.5)
                case '['  =>
                  val s  = vc.spec
                  val vs = vc.value
                  val vNew = if (s.warp == IntWarp) {
                    vs.map(v => s.inverseMap(s.map(v) - 1))
                  } else vs.map(_ - 0.005)
                  vNew.map(math.max(0.0, _))

                case ']'  =>
                  val s  = vc.spec
                  val vs = vc.value
                  val vNew = if (s.warp == IntWarp) {
                    vs.map(v => s.inverseMap(s.map(v) + 1))
                  } else vs.map(_ + 0.005)
                  vNew.map(math.min(1.0, _))

                case '{'  =>  // decrease channel spacing
                  val vs      = vc.value
                  val max     = vs.max
                  val min     = vs.min
                  val mid     = (min + max) / 2
                  val newMin  = math.min(mid, min + 0.0025)
                  val newMax  = math.max(mid, max - 0.0025)
                  if (newMin == min && newMax == max) Vector.empty else {
                    import numbers.Implicits._
                    vs.map(_.linlin(min, max, newMin, newMax))
                  }

                case '}'  =>  // increase channel spacing
                  val vs      = vc.value
                  val max     = vs.max
                  val min     = vs.min
                  val newMin  = math.max(0.0, min - 0.0025)
                  val newMax  = math.min(1.0, max + 0.0025)
                  if (newMin == min && newMax == max) Vector.empty else {
                    import numbers.Implicits._
                    if (min == max) { // all equal -- use a random spread
                      vs.map(in => (in + math.random.linlin(0.0, 1.0, -0.0025, +0.0025)).clip(0.0, 1.0))
                    } else {
                      vs.map(_.linlin(min, max, newMin, newMax))
                    }
                  }

                case _ => Vector.empty
              }
              if (v.nonEmpty) vc.setControl(v, instant = true)

              lastVi    = vi
              lastChar  = thisChar
              lastTyped = thisTyped

            case _ =>
          }

        case _ =>
      }
    }

    def lostOwnership(clipboard: Clipboard, contents: Transferable): Unit = ()
  }
}