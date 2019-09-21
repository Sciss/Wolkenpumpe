package de.sciss.lucre.synth

object Peek {
  def rootNode(s: Server): Group =
    Group.wrap(s, s.peer.rootNode)
}
