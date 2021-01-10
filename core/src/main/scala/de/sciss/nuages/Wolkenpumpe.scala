/*
 *  Wolkenpumpe.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2021 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages

import java.awt.{Color, Font}
import de.sciss.proc.SoundProcesses
import de.sciss.synth.UGenSource
import de.sciss.synth.proc.graph.Param

import scala.swing.Component

object Wolkenpumpe {
  // var ALWAYS_CONTROL = true

  private lazy val _initFont: Font = {
    val url = Wolkenpumpe.getClass.getResource("BellySansCondensed.ttf")
    // val is  = Wolkenpumpe.getClass.getResourceAsStream("BellySansCondensed.ttf")
    if (url == null) {
      new Font(Font.SANS_SERIF, Font.PLAIN, 1)
    } else {
      val is = url.openStream()
      val res = Font.createFont(Font.TRUETYPE_FONT, is)
      is.close()
      res
    }
    //      // "SF Movie Poster Condensed"
    //      new Font( "BellySansCondensed", Font.PLAIN, 12 )
  }

  private var _condensedFont: Font = _

  /** A condensed font for GUI usage. This is in 12 pt size,
    * so consumers must rescale.
    */
  def condensedFont: Font = {
    if (_condensedFont == null) _condensedFont = _initFont
    _condensedFont
  }
  def condensedFont_=(value: Font): Unit =
    _condensedFont = value

  private[this] lazy val _isSubmin = javax.swing.UIManager.getLookAndFeel.getID == "submin"

  //  def isSubmin: Boolean = _isSubmin

  def mkBlackWhite(c: Component): Unit =
    if (!_isSubmin) {
      c.background = Color.black
      c.foreground = Color.white
    }

  def init(): Unit = {
    SoundProcesses.init()
    ParamSpec     .init()
    Warp          .init()
    Nuages        .init()

    UGenSource.addProductReaderSq(Param :: Nil)
  }
}