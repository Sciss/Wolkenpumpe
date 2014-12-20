/*
 *  VisualNodeImpl.scala
 *  (Wolkenpumpe)
 *
 *  Copyright (c) 2008-2014 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.nuages
package impl

import de.sciss.lucre.synth.Sys
import prefuse.data.{Node => PNode}

trait VisualNodeImpl[S <: Sys[S]] extends VisualDataImpl[S] with VisualNode[S] {
  var pNode: PNode = _

  final protected def atomic[A](fun: S#Tx => A): A = main.transport.scheduler.cursor.step(fun)
}
