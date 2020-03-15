/*
 *  KeyControl.scala
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

import java.awt.datatransfer.{DataFlavor, Transferable, UnsupportedFlavorException}
import java.awt.event.{KeyEvent, MouseEvent}
import java.awt.geom.Point2D
import java.awt.{Color, Point}

import javax.swing.KeyStroke
import javax.swing.event.{AncestorEvent, AncestorListener, DocumentEvent, DocumentListener}
import de.sciss.lucre.expr.StringObj
import de.sciss.lucre.stm
import de.sciss.lucre.stm.{Disposable, Folder, IdentifierMap, Obj}
import de.sciss.lucre.synth.Sys
import de.sciss.nuages.NuagesPanel._
import de.sciss.swingplus.ListView
import de.sciss.synth.proc.{ObjKeys, Proc}
import prefuse.controls.{Control, ControlAdapter}
import prefuse.visual.{EdgeItem, NodeItem, VisualItem}

import scala.annotation.switch
import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.stm.{InTxn, TMap, TxnExecutor}
import scala.swing.event.Key
import scala.swing.{Orientation, ScrollPane, TextField}

/** A control that handles keyboard input.
  *
  * - shortcuts as defined per `Nuages.attrShortcut`
  * - enter to type a generator or filter
  * - `1` and `2`: zoom levels
  * - `O` pan to next output
  * - forwards to `itemKeyPressed`, `itemKeyReleased`, `itemKeyTypes` of `NuagesData`
  *   (thus making all other keyboard control such as parameter adjustments possible)
  */
object KeyControl {
  def apply[S <: Sys[S]](main: NuagesPanel[S])(implicit tx: S#Tx): Control with Disposable[S#Tx] = {
    val res = new Impl(main)
    res.init()
    res
  }

  /** The keyboard event.
    *
    * @param char    the typed character
    * @param count   the type repeat count
    */
  final case class Typed(char: Char, count: Int)

  final case class Pressed(code: Key.Value, modifiers: Int)

  private def internalFlavor[A](implicit ct: reflect.ClassTag[A]): DataFlavor =
    new DataFlavor(s"""${DataFlavor.javaJVMLocalObjectMimeType};class="${ct.runtimeClass.getName}"""")

  final class ControlDrag(val values: Vec[Double], val spec: ParamSpec) extends Transferable {
    def getTransferDataFlavors: Array[DataFlavor] = Array(ControlFlavor)
    def isDataFlavorSupported(_flavor: DataFlavor): Boolean = _flavor == ControlFlavor

    def getTransferData(_flavor: DataFlavor): AnyRef  = {
      if (!isDataFlavorSupported(_flavor)) throw new UnsupportedFlavorException(_flavor)
      this
    }
  }

  val ControlFlavor: DataFlavor = internalFlavor[ControlDrag]

  private trait Category[S <: Sys[S]] extends Disposable[S#Tx] {
    def get(ks  : KeyStroke): Option[stm.Source[S#Tx, Obj[S]]]
    def get(name: String   ): Option[stm.Source[S#Tx, Obj[S]]]

    def names: Iterator[String]
  }

  private abstract class CategoryImpl[S <: Sys[S]] extends Category[S] {
    protected def observer: Disposable[S#Tx]
    protected def idMap: IdentifierMap[S#Id, S#Tx, KeyStroke]

    private val keyMap  = TMap.empty[KeyStroke, stm.Source[S#Tx, Obj[S]]]
    private val nameMap = TMap.empty[String   , stm.Source[S#Tx, Obj[S]]]

    final def dispose()(implicit tx: S#Tx): Unit = observer.dispose()

    def names: Iterator[String] = nameMap.single.keysIterator

    protected final def elemAdded(elem: Obj[S])(implicit tx: S#Tx): Unit = {
      implicit val itx: InTxn = tx.peer
      val attr    = elem.attr
      val source  = tx.newHandle(elem)  // eagerly because we expect `name` to be present
      attr.$[StringObj](Nuages.attrShortcut).foreach { expr =>
        Option(KeyStroke.getKeyStroke(expr.value)).foreach { ks =>
          keyMap.put(ks, source)(tx.peer)
        }
      }
      attr.$[StringObj](ObjKeys.attrName).foreach { expr =>
        nameMap.put(expr.value, source)
      }
    }

    protected final def elemRemoved(elem: Obj[S])(implicit tx: S#Tx): Unit = {
      implicit val itx: InTxn = tx.peer
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
    extends ControlAdapter with Disposable[S#Tx] /* with ClipboardOwner */ {

    private[this] var filters   : Category[S] = _
    private[this] var generators: Category[S] = _
    private[this] var collectors: Category[S] = _

    private[this] val lastPt = new Point
    private[this] val p2d    = new Point2D.Float // throw-away

    private[this] var lastVi      : VisualItem  = _
    private[this] var lastChar    : Char        = _
    private[this] var lastTyped   : Long        = _
    private[this] var typedCount  : Int         = 0

    private[this] var lastCollector = null : NodeItem

    // we track the mouse cursor position
    override def mousePressed(e: MouseEvent): Unit = main.display.requestFocus()
    override def mouseDragged(e: MouseEvent): Unit = lastPt.setLocation(e.getX, e.getY)
    override def mouseMoved  (e: MouseEvent): Unit = lastPt.setLocation(e.getX, e.getY)

    private def mkEmptyCategory(): Category[S] = new Category[S] {
      def dispose()(implicit tx: S#Tx): Unit = ()

      def get(ks  : KeyStroke): Option[stm.Source[S#Tx, Obj[S]]] = None
      def get(name: String   ): Option[stm.Source[S#Tx, Obj[S]]] = None

      def names: Iterator[String] = Iterator.empty
    }

    private def mkCategory(f: Folder[S])(implicit tx: S#Tx): Category[S] = new CategoryImpl[S] {
      protected val idMap: IdentifierMap[S#Id, S#Tx, KeyStroke] = tx.newInMemoryIdMap[KeyStroke]

      protected val observer: Disposable[S#Tx] = f.changed.react { implicit tx =>upd =>
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
      collectors  = n.collectors.fold(mkEmptyCategory())(mkCategory)
    }

    def dispose()(implicit tx: S#Tx): Unit = {
      filters   .dispose()
      generators.dispose()
    }

    override def keyPressed(e: KeyEvent): Unit = {
      val handled: Boolean = if (e.getKeyCode == KeyEvent.VK_ENTER) {
        showCategoryInput(generators) { implicit tx => (gen, pt) =>
          main.createGenerator(gen, None, pt)
        }
        true

      } else {
        val ks = KeyStroke.getKeyStroke(e.getKeyCode, e.getModifiers)
        val genOpt = generators.get(ks)
        genOpt.foreach { objH =>
          val display = main.display
          display.getAbsoluteCoordinate(lastPt, p2d)
          main.cursor.step { implicit tx =>
            main.createGenerator(objH(), colOpt = None, pt = p2d)
          }
        }
        genOpt.isDefined
      }

      if (!handled) globalKeyPressed(e)
    }

    override def itemKeyPressed(vi: VisualItem, e: KeyEvent): Unit = {
      // println(s"itemKeyPressed '${e.getKeyChar}'")
      val handled: Boolean = vi match {
        case ei: EdgeItem =>
          def perform[A](fun: (NuagesOutput[S], NuagesAttribute.Input[S], Point2D) => A): Option[A] = {
            val nSrc  = ei.getSourceItem
            val nTgt  = ei.getTargetItem
            val vis   = main.visualization
            val _ve   = vis.getVisualItem(NuagesPanel.GROUP_GRAPH, ei)
            (vis.getRenderer(nSrc), vis.getRenderer(nTgt)) match {
              case (_: NuagesShapeRenderer[_], _: NuagesShapeRenderer[_]) =>
                val srcData = nSrc.get(COL_NUAGES).asInstanceOf[NuagesData[S]]
                val tgtData = nTgt.get(COL_NUAGES).asInstanceOf[NuagesData[S]]
                if (srcData == null || tgtData == null) None else
                  (srcData, tgtData) match {
                    case (vOut: NuagesOutput[S], vIn: NuagesAttribute.Input[S]) =>
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
            perform { (vOut, vIn, _ /* pt */) =>
              showCategoryInput(filters) { implicit tx => (obj, pt0) =>
                val pred    = vOut.output
                val inAttr  = vIn.attribute
                // val succ    = inAttr.parent.obj -> inAttr.key
                // main.display.getTransform.transform(pt0, p2d)
                main.insertFilter(pred = pred, succ = inAttr, fltSrc = obj, fltPt = pt0)
              }
            }
            true

          } else {
            val ks = KeyStroke.getKeyStroke(e.getKeyCode, e.getModifiers)
            val filterOpt = filters.get(ks)
            filterOpt.foreach { objH =>
              perform { (vOut, vIn, pt) =>
                main.cursor.step { implicit tx =>
                  val pred    = vOut.output
                  val inAttr  = vIn.attribute
                  // val succ    = inAttr.parent.obj -> inAttr.key
                  // println(s"insertFilter(pred = $pred, succ = $succ)")
                  main.insertFilter(pred = pred, succ = inAttr, fltSrc = objH(), fltPt = pt)
                }
              }
            }
            filterOpt.isDefined
          }

        case ni: NodeItem =>
          ni.get(COL_NUAGES) match {
            case d: NuagesData[S] =>
              val e1 = Pressed(code = Key(e.getKeyCode), modifiers = e.getModifiers)
              val _handled = d.itemKeyPressed(vi, e1)
              _handled || (d match {
                case vOut: NuagesOutput[S] if vOut.name == Proc.mainOut =>
                  def perform[A](fun: Point2D => A): A = {
                    val vis   = main.visualization
                    val _ve   = vis.getVisualItem(NuagesPanel.GROUP_GRAPH, ni)
                    val r     = _ve.getBounds
                    p2d.setLocation(r.getCenterX, r.getCenterY)
                    fun(p2d)
                  }

                  if (e.getKeyCode == KeyEvent.VK_ENTER) {
                    perform { _ /* pt */ =>
                      val category = if (e.isShiftDown) collectors else filters
                      showCategoryInput(category) { implicit tx => (obj, pt0) =>
                        val pred = vOut.output
                        main.appendFilter(pred = pred, fltSrc = obj, colSrcOpt = None, fltPt = pt0)
                      }
                    }
                    true

                  } else {
                    val ks = KeyStroke.getKeyStroke(e.getKeyCode, e.getModifiers)
                    val filterOpt = filters.get(ks)
                    filterOpt.foreach { objH =>
                      perform { pt =>
                        main.cursor.step { implicit tx =>
                          val pred = vOut.output
                          main.appendFilter(pred = pred, fltSrc = objH(), colSrcOpt = None, fltPt = pt)
                        }
                      }
                    }
                    filterOpt.isDefined
                  }
                case _ => false
              })

            case _ => false
          }

        case _ => false
      }

      if (!handled) globalKeyPressed(e)
    }

    private def globalKeyPressed(e: KeyEvent): Unit =
      (e.getKeyCode: @switch) match {
        case KeyEvent.VK_1 =>
          zoom(1.0)
        case KeyEvent.VK_2 =>
          zoom(2.0)
        case KeyEvent.VK_O =>
          panToNextCollector()
        case _   =>
      }

    private def panToNextCollector(): Unit = {
      val display = main.display
      if (display.isTranformInProgress) return

      // yes, this code is ugly, but so is
      // chasing a successor on a non-cyclic iterator...
      lastCollector = TxnExecutor.defaultAtomic { implicit tx =>
        val it = main.visualGraph.nodes()
        var first = null : NodeItem
        var last  = null : NodeItem
        var pred  = null : NodeItem
        var stop  = false
        while (it.hasNext && !stop) {
          val ni = it.next().asInstanceOf[NodeItem]
          import de.sciss.lucre.stm.TxnLike.wrap
          val ok = ni.get(NuagesPanel.COL_NUAGES) match {
            case vo: NuagesObj[_] => vo.isCollector
            case _ => false
          }
          if (ok) {
            if (first == null) first = ni
            pred = last
            last = ni
            if (pred == lastCollector) stop = true
          }
        }
        if (stop) last else first
      }
      if (lastCollector != null) {
        val vi = main.visualization.getVisualItem(NuagesPanel.GROUP_GRAPH, lastCollector)
        if (vi != null) {
          // println(s"Index = $i")
          val b = vi.getBounds
          p2d.setLocation(b.getCenterX, b.getCenterY) // vi.getX + b.getWidth/2, vi.getY + b.getHeight/2)
          display.animatePanToAbs(p2d, 500 /* 200 */)
        }
      }
    }

    private def zoom(v: Double): Unit = {
      val display = main.display
      if (display.isTranformInProgress) return

      display.getAbsoluteCoordinate(lastPt, p2d)
      val scale = display.getScale
      // display.zoomAbs(p2d, v / scale)
      display.animateZoomAbs(p2d, v / scale, 500 /* 200 */)
    }

    private def showCategoryInput(c: Category[S])(complete: S#Tx => (Obj[S], Point2D) => Unit): Unit = {
      val lpx = lastPt.x
      val lpy = lastPt.y
      val p: OverlayPanel = new OverlayPanel(Orientation.Horizontal) { panel =>
        val ggName = new TextField(12)
        Wolkenpumpe.mkBlackWhite(ggName)
        ggName.peer.addAncestorListener(new AncestorListener {
          def ancestorRemoved(e: AncestorEvent): Unit = ()
          def ancestorMoved  (e: AncestorEvent): Unit = ()
          def ancestorAdded  (e: AncestorEvent): Unit = ggName.requestFocus()
        })

        var candidates: Vec[String] = Vector.empty

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
        // contents += new BasicPanel(Orientation.Vertical) {
          contents += ggName
          contents += new ScrollPane(ggCandidates) // new ListView(mCategList)
        // }
        onComplete {
          val sel = ggCandidates.selection.items
          close()
          if (sel.size == 1) {
            val name = sel.head
            c.get(name).foreach { source =>
              // val b         = panel.bounds
              // p2d.setLocation(b.getCenterX, b.getCenterY)
              p2d.setLocation(lpx, lpy)
              val displayPt = main.display.getAbsoluteCoordinate(p2d, null)
              main.cursor.step { implicit tx =>
                complete(tx)(source(), displayPt)
              }
            }
          }
        }
      }

      val dim = p.preferredSize
      val dh  = main.display.getHeight
      val pt  = new Point(lpx - dim.width/2, /* lpy - 12 */ dh - dim.height)

      main.showOverlayPanel(p, Some(pt))
    }

    override def itemKeyReleased(vi: VisualItem, e: KeyEvent): Unit =
      vi match {
        case ni: NodeItem =>
          ni.get(COL_NUAGES) match {
            case  d: NuagesData[S] =>
              val e1 = Pressed(code = Key(e.getKeyCode), modifiers = e.getModifiers)
              d.itemKeyReleased(vi, e1)
            case _ =>
          }

        case _ =>
      }

    override def itemKeyTyped(vi: VisualItem, e: KeyEvent): Unit =
      vi match {
        case ni: NodeItem =>
          ni.get(COL_NUAGES) match {
            case d: NuagesData[S] =>
              // check
              val thisChar    = e.getKeyChar
              val thisTyped   = System.currentTimeMillis()
              val isRepeat    = (lastVi == vi && lastChar == thisChar) && thisTyped - lastTyped < 500
              typedCount      = if (isRepeat) typedCount + 1 else 0
              lastVi          = vi
              lastChar        = thisChar
              lastTyped       = thisTyped

              val e1 = Typed(char = e.getKeyChar, count = typedCount)
              d.itemKeyTyped(vi, e1)

            case _ =>
          }
        case _ =>
      }

    // def lostOwnership(clipboard: Clipboard, contents: Transferable): Unit = ()
  }
}