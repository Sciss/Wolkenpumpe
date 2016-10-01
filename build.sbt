name         := "wolkenpumpe"
version      := "0.34"
organization := "de.sciss"
homepage     := Some(url(s"https://github.com/Sciss/${name.value}"))
description  := "A Prefuse based visual interface for SoundProcesses, a sound synthesis framework"
licenses     := Seq("GPL v2+" -> url("http://www.gnu.org/licenses/gpl-2.0.txt"))
scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
   "de.sciss" %  "prefuse-core"   % "0.21",
   "de.sciss" %% "soundprocesses" % "0.34"
)

scalacOptions ++= Seq("-deprecation", "-unchecked")

// ---- publishing ----

publishMavenStyle := true

publishTo :=
   Some(if (isSnapshot.value)
      "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
   else
      "Sonatype Releases"  at "https://oss.sonatype.org/service/local/staging/deploy/maven2"
   )

publishArtifact in Test := false

pomIncludeRepository := { _ => false }

pomExtra :=
<scm>
  <url>git@github.com:Sciss/Wolkenpumpe.git</url>
  <connection>scm:git:git@github.com:Sciss/Wolkenpumpe.git</connection>
</scm>
<developers>
   <developer>
      <id>sciss</id>
      <name>Hanns Holger Rutz</name>
      <url>http://www.sciss.de</url>
   </developer>
</developers>

