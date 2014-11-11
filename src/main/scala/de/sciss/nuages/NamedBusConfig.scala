package de.sciss.nuages

final case class NamedBusConfig(name: String, offset: Int, numChannels: Int) {
  def stopOffset: Int = offset + numChannels
}