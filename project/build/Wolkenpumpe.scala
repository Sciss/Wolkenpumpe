import xml._
import sbt.{ FileUtilities => FU, _}

class WolkenpumpeProject( info: ProjectInfo ) extends DefaultProject( info ) {
   val scalaColliderSwing  = "de.sciss" %% "scalacolliderswing" % "0.27"
   val soundProcesses      = "de.sciss" %% "soundprocesses" % "0.23"

   // for some reason, we need to add the jsyntaxpane repo here again...
   val repo1               = "Clojars Repository" at "http://clojars.org/repo"

   // ---- publishing ----

   override def managedStyle  = ManagedStyle.Maven
   val publishTo              = "Scala Tools Nexus" at "http://nexus.scala-tools.org/content/repositories/releases/"

//   override def packageDocsJar= defaultJarPath( "-javadoc.jar" )
//   override def packageSrcJar = defaultJarPath( "-sources.jar" )
//   val sourceArtifact         = Artifact.sources( artifactID )
//   val docsArtifact           = Artifact.javadoc( artifactID )
//   override def packageToPublishActions = super.packageToPublishActions ++ Seq( packageDocs, packageSrc )

   override def pomExtra =
      <licenses>
        <license>
          <name>GPL v2+</name>
          <url>http://www.gnu.org/licenses/gpl-2.0.txt</url>
          <distribution>repo</distribution>
        </license>
      </licenses>

   Credentials( Path.userHome / ".ivy2" / ".credentials", log )
}