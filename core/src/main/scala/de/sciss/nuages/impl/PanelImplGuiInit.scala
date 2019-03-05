/*
 *  PanelImplGuiInit.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2019 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import java.awt.geom.Point2D
import java.awt.{Color, Graphics2D, RenderingHints}
import javax.swing.{BoundedRangeModel, DefaultBoundedRangeModel, JPanel}

import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.synth.Sys
import de.sciss.numbers
import prefuse.action.{ActionList, RepaintAction}
import prefuse.action.assignment.ColorAction
import prefuse.action.layout.graph.ForceDirectedLayout
import prefuse.activity.Activity
import prefuse.controls.{Control, WheelZoomControl, ZoomControl}
import prefuse.data.{Graph, Table, Tuple}
import prefuse.data.event.TupleSetListener
import prefuse.data.tuple.{DefaultTupleSet, TupleSet}
import prefuse.render.{DefaultRendererFactory, EdgeRenderer, PolygonRenderer}
import prefuse.util.ColorLib
import prefuse.visual.{AggregateTable, VisualGraph, VisualItem}
import prefuse.visual.expression.InGroupPredicate
import prefuse.{Constants, Display, Visualization}

import scala.swing.Component

trait PanelImplGuiInit[S <: Sys[S]] extends ComponentHolder[Component] {
  _: NuagesPanel[S] =>

  import PanelImpl.{GROUP_NODES, GROUP_EDGES, AGGR_PROC, ACTION_COLOR, ACTION_LAYOUT, LAYOUT_TIME}
  import NuagesPanel.{GROUP_GRAPH, GROUP_SELECTION, COL_NUAGES}

  type C = Component

  // ---- abstract ----

  protected def keyControl: Control
  protected def main: NuagesPanel[S]

  // ---- impl ----

  private[this] var _vis: Visualization = _
  private[this] var _dsp: Display       = _
  private[this] var _g  : Graph         = _
  private[this] var _vg : VisualGraph   = _

  private[this] var _aggrTable: AggregateTable = _

  def display      : Display        = _dsp
  def visualization: Visualization  = _vis
  def graph        : Graph          = _g
  def visualGraph  : VisualGraph    = _vg
  def aggrTable    : AggregateTable = _aggrTable

  private[this] var _mGlideTime : BoundedRangeModel   = _

  final def glideTime: Float = {
    import numbers.Implicits._
    val view = _mGlideTime.getValue
    view.linLin(_mGlideTime.getMinimum, _mGlideTime.getMaximum, 0f, 1f)
  }

  final def glideTime_=(value: Float): Unit = {
    import numbers.Implicits._
    val view = math.round(value.linLin(0f, 1f, _mGlideTime.getMinimum, _mGlideTime.getMaximum))
    _mGlideTime.setValue(view)
  }

  @volatile
  final var acceptGlideTime: Boolean = false

  @volatile
  final var glideTimeSource: String = ""

  final def glideTimeModel: BoundedRangeModel = _mGlideTime

  protected def guiInit(): Unit = {
    _vis = new Visualization
    _dsp = new Display(_vis) {
      // setFocusable(true)

      override def setRenderingHints(g: Graphics2D): Unit = {
        super.setRenderingHints(g)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
          if (m_highQuality) RenderingHints.VALUE_STROKE_PURE else RenderingHints.VALUE_STROKE_NORMALIZE)
      }
    }

    _g     = new Graph
    _vg    = _vis.addGraph(GROUP_GRAPH, _g)
    _vg.addColumn(COL_NUAGES, classOf[AnyRef])
    _aggrTable = _vis.addAggregates(AGGR_PROC)
    _aggrTable .addColumn(VisualItem.POLYGON, classOf[Array[Float]])

    val procRenderer = new NuagesShapeRenderer(50)
    val edgeRenderer = new EdgeRenderer(Constants.EDGE_TYPE_LINE, Constants.EDGE_ARROW_FORWARD) {
      //        override def render(g: Graphics2D, item: VisualItem): Unit = {
      //          g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
      //          super.render(g, item)
      //        }

      // same as original method but with much larger border to ease mouse control
      override def locatePoint(p: Point2D, item: VisualItem): Boolean = {
        val s = getShape(item)
        s != null && {
          val width     = math.max(20 /* 2 */, getLineWidth(item))
          val halfWidth = width / 2.0
          s.intersects(p.getX - halfWidth, p.getY - halfWidth, width, width)
        }
      }
    }
    // edgeRenderer.setDefaultLineWidth(2)
    val aggrRenderer = new PolygonRenderer(Constants.POLY_TYPE_CURVE)
    aggrRenderer.setCurveSlack(0.15f)

    val rf = new DefaultRendererFactory(procRenderer)
    rf.add(new InGroupPredicate(GROUP_EDGES), edgeRenderer)
    rf.add(new InGroupPredicate(AGGR_PROC  ), aggrRenderer)
    _vis.setRendererFactory(rf)

    // colors
    val actionNodeStroke  = new ColorAction(GROUP_NODES, VisualItem.STROKECOLOR, ColorLib.rgb(255, 255, 255))
    actionNodeStroke.add(VisualItem.HIGHLIGHT, ColorLib.rgb(255, 255, 0))
    // actionNodeStroke.add(VisualItem.HIGHLIGHT, ColorLib.rgb(220, 220, 0))
    val actionNodeFill    = new ColorAction(GROUP_NODES, VisualItem.FILLCOLOR  , ColorLib.rgb(0, 0, 0))
    actionNodeFill.add(VisualItem.HIGHLIGHT, ColorLib.rgb(63, 63, 0))
    val actionTextColor   = new ColorAction(GROUP_NODES, VisualItem.TEXTCOLOR  , ColorLib.rgb(255, 255, 255))

    val actionEdgeColor   = new ColorAction(GROUP_EDGES, VisualItem.STROKECOLOR, ColorLib.rgb(255, 255, 255))
    val actionAggrFill    = new ColorAction(AGGR_PROC  , VisualItem.FILLCOLOR  , ColorLib.rgb(80, 80, 80))
    // actionAggrFill.add(VisualItem.HIGHLIGHT, ColorLib.rgb(220, 220, 0))
    val actionAggrStroke  = new ColorAction(AGGR_PROC  , VisualItem.STROKECOLOR, ColorLib.rgb(255, 255, 255))

    val lay = new ForceDirectedLayout(GROUP_GRAPH)
    val sim = lay.getForceSimulator
    // val forces = sim.getForces.map { f => (f.getClass.getSimpleName, f) } (breakOut)

    val forceMap = Map(
      // ("NBodyForce" , "GravitationalConstant") -> 0f, // -0.01f,
      ("NBodyForce", "Distance") -> 200.0f
      // ("NBodyForce" , "BarnesHutTheta"       ) -> 0.4f,
      // ("DragForce"  , "DragCoefficient"      ) -> 0.015f,
    )

    sim.getForces.foreach { force =>
      val fName = force.getClass.getSimpleName
      for (i <- 0 until force.getParameterCount) {
        val pName = force.getParameterName(i)
        forceMap.get((fName, pName)).foreach { value =>
          force.setParameter(i, value)
        }
      }
    }

    // quick repaint
    val actionColor = new ActionList()
    actionColor.add(actionTextColor)
    actionColor.add(actionNodeStroke)
    actionColor.add(actionNodeFill)
    actionColor.add(actionEdgeColor)
    actionColor.add(actionAggrFill)
    actionColor.add(actionAggrStroke)
    _vis.putAction(ACTION_COLOR, actionColor)

    val actionLayout = new ActionList(Activity.INFINITY, LAYOUT_TIME)
    actionLayout.add(lay)
    actionLayout.add(new PrefuseAggregateLayout(AGGR_PROC))
    // actionLayout.add(actionNodeFill)
    actionLayout.add(new RepaintAction())
    _vis.putAction(ACTION_LAYOUT, actionLayout)
    _vis.alwaysRunAfter(ACTION_COLOR, ACTION_LAYOUT)

    // ------------------------------------------------

    // initialize the display
    _dsp.setSize(960, 640)
    _dsp.addControlListener(new ZoomControl     ())
    _dsp.addControlListener(new WheelZoomControl())
    _dsp.addControlListener(new PanControl        )
    _dsp.addControlListener(new DragAndMouseDelegateControl     (_vis))
    _dsp.addControlListener(new ClickControl    (main))
    _dsp.addControlListener(new ConnectControl  (main))
    _dsp.addControlListener(keyControl)
    _dsp.setHighQuality(true)
    new GlobalControl(main)

    // ---- rubber band test ----
    mkRubberBand(rf)

    // ------------------------------------------------

    edgeRenderer.setHorizontalAlignment1(Constants.CENTER)
    edgeRenderer.setHorizontalAlignment2(Constants.CENTER)
    edgeRenderer.setVerticalAlignment1  (Constants.CENTER)
    edgeRenderer.setVerticalAlignment2  (Constants.CENTER)

    _dsp.setForeground(Color.WHITE)
    _dsp.setBackground(Color.BLACK)

    //      setLayout( new BorderLayout() )
    //      add( display, BorderLayout.CENTER )
    val p = new JPanel
    p.setLayout(new PanelLayout(_dsp))
    p.add(_dsp)

    _vis.run(ACTION_COLOR)

    _mGlideTime = new DefaultBoundedRangeModel(0, 1, 0, 100)

    component   = Component.wrap(p)
  }

  private def mkRubberBand(rf: DefaultRendererFactory): Unit = {
    val selectedItems = new DefaultTupleSet
    _vis.addFocusGroup(GROUP_SELECTION, selectedItems)
    selectedItems.addTupleSetListener(new TupleSetListener {
      def tupleSetChanged(ts: TupleSet, add: Array[Tuple], remove: Array[Tuple]): Unit = {
        add.foreach {
          case vi: VisualItem => vi.setHighlighted(true)
        }
        remove.foreach {
          case vi: VisualItem => vi.setHighlighted(false)
        }
        _vis.run(ACTION_COLOR)
      }
    })

    val rubberBandTable = new Table
    rubberBandTable.addColumn(VisualItem.POLYGON, classOf[Array[Float]])
    rubberBandTable.addRow()
    _vis.add("rubber", rubberBandTable)
    val rubberBand = _vis.getVisualGroup("rubber").tuples().next().asInstanceOf[VisualItem]
    rubberBand.set(VisualItem.POLYGON, new Array[Float](8))
    rubberBand.setStrokeColor(ColorLib.color(ColorLib.getColor(255, 0, 0)))

    // render the rubber band with the default polygon renderer
    val rubberBandRenderer = new PolygonRenderer(Constants.POLY_TYPE_LINE)
    rf.add(new InGroupPredicate("rubber"), rubberBandRenderer)

    _dsp.addControlListener(new RubberBandSelect(rubberBand))
  }
}