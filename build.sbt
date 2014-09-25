name               := "wolkenpumpe"

version            := "1.0.0-SNAPSHOT"

organization       := "de.sciss"

homepage           := Some(url("https://github.com/Sciss/" + name.value))

description        := "A Prefuse based visual interface for SoundProcesses, a sound synthesis framework"

licenses           := Seq("GPL v2+" -> url( "http://www.gnu.org/licenses/gpl-2.0.txt"))

scalaVersion       := "2.11.2"

crossScalaVersions := Seq("2.11.2", "2.10.4")

lazy val soundProcessesVersion  = "2.7.0-SNAPSHOT"

lazy val prefuseVersion         = "1.0.0"

lazy val lucreSwingVersion      = "0.5.0"

lazy val swingPlusVersion       = "0.2.0"

libraryDependencies ++= Seq(
  "de.sciss" %% "soundprocesses" % soundProcessesVersion,
  "de.sciss" %  "prefuse-core"   % prefuseVersion,
  "de.sciss" %% "lucreswing"     % lucreSwingVersion,
  "de.sciss" %% "swingplus"      % swingPlusVersion
)

// retrieveManaged := true

scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-encoding", "utf8", "-Xfuture")

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

pomExtra := { val n = name.value
<scm>
  <url>git@github.com:Sciss/{n}.git</url>
  <connection>scm:git:git@github.com:Sciss/{n}.git</connection>
</scm>
<developers>
   <developer>
      <id>sciss</id>
      <name>Hanns Holger Rutz</name>
      <url>http://www.sciss.de</url>
   </developer>
</developers>
}

// ---- ls.implicit.ly ----

seq(lsSettings :_*)

(LsKeys.tags   in LsKeys.lsync) := Seq("sound-synthesis", "gui", "sound", "music", "supercollider")

(LsKeys.ghUser in LsKeys.lsync) := Some("Sciss")

(LsKeys.ghRepo in LsKeys.lsync) := Some(name.value)

