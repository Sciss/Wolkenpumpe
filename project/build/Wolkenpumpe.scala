import xml._
import sbt.{ FileUtilities => FU, _}

class WolkenpumpeProject( info: ProjectInfo ) extends DefaultProject( info ) {
   val scalaColliderSwing  = "de.sciss" %% "scalacolliderswing" % "0.26"
   val soundProcesses      = "de.sciss" %% "soundprocesses" % "0.23"
//   // for some reason, we need to add the snapshot repos here again...
//   val ccstmRepo           = "CCSTM Release Repository at PPL" at "http://ppl.stanford.edu/ccstm/repo-releases"
//   val ccstmSnap           = "CCSTM Snapshot Repository at PPL" at "http://ppl.stanford.edu/ccstm/repo-snapshots"
//   val prefuse             = "prefuse" % "prefuse" % "beta-SNAPSHOT" from "http://github.com/downloads/Sciss/ScalaColliderSwing/prefuse-beta-SNAPSHOT.jar"
   val prefuse             = "de.sciss" % "prefuse" % "0.20"

   // for some reason, we need to add the jsyntaxpane repo here again...
   val repo1               = "Clojars Repository" at "http://clojars.org/repo"
}