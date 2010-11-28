package de.sciss.nuages

import de.sciss.synth.{AudioBus, Server}
import collection.immutable.{IndexedSeq => IIdxSeq}

/**
 *    @version 0.10, 18-Jul-10
 */
case class NuagesConfig(
   server: Server,
   masterChannels: Option[ IIdxSeq[ Int ]],
   soloChannels: Option[ IIdxSeq[ Int ]],
   recordPath: Option[ String ],
   meters: Boolean = false
)