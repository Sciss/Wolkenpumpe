name := "wolkenpumpe"

version := "0.30"

organization := "de.sciss"

scalaVersion := "2.9.1"

libraryDependencies ++= Seq(
//   "de.sciss" %% "scalacolliderswing" % "0.30",
   "de.sciss" % "prefuse-core" % "0.21",
   "de.sciss" %% "scalacollider" % "0.30",
   "de.sciss" %% "soundprocesses" % "0.30"
)

retrieveManaged := true

scalacOptions ++= Seq( "-deprecation", "-unchecked" )

// ---- publishing ----

publishTo <<= version { (v: String) =>
   Some( "Scala Tools Nexus" at "http://nexus.scala-tools.org/content/repositories/".+(
      if( v.endsWith( "-SNAPSHOT")) "snapshots/" else "releases/"
   ))
}

pomExtra :=
<licenses>
  <license>
    <name>GPL v2+</name>
    <url>http://www.gnu.org/licenses/gpl-2.0.txt</url>
    <distribution>repo</distribution>
  </license>
</licenses>

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

