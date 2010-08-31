import xml._
import sbt.{ FileUtilities => FU, _}

/**
 *    @version 0.12, 02-Aug-10
 */
class WolkenpumpeProject( info: ProjectInfo ) extends DefaultProject( info ) {
   val scalaColliderSwing  = "de.sciss" %% "scalacolliderswing" % "0.21"
   val soundProcesses      = "de.sciss" %% "soundprocesses" % "0.18"
   // for some reason, we need to add the snapshot repos here again...
   val ccstmRepo           = "CCSTM Release Repository at PPL" at "http://ppl.stanford.edu/ccstm/repo-releases"
   val ccstmSnap           = "CCSTM Snapshot Repository at PPL" at "http://ppl.stanford.edu/ccstm/repo-snapshots"
   val prefuse             = "prefuse" % "prefuse" % "beta-SNAPSHOT" from "http://github.com/downloads/Sciss/ScalaColliderSwing/prefuse-beta-SNAPSHOT.jar"
}
