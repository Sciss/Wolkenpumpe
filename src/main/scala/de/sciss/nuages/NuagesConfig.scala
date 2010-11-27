package de.sciss.nuages

import de.sciss.synth.{AudioBus, Server}

/**
 *    @version 0.10, 18-Jul-10
 */
case class NuagesConfig(
   server: Server,
   masterBus: Option[ AudioBus ],
   soloBus: Option[ AudioBus ],
   recordPath: Option[ String ],
   meters: Boolean = false
)