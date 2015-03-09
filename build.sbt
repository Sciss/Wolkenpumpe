name               := "Wolkenpumpe"

version            := "1.2.0"

organization       := "de.sciss"

homepage           := Some(url("https://github.com/Sciss/" + name.value))

description        := "A Prefuse based visual interface for SoundProcesses, a sound synthesis framework"

licenses           := Seq("GPL v2+" -> url( "http://www.gnu.org/licenses/gpl-2.0.txt"))

scalaVersion       := "2.11.6"

crossScalaVersions := Seq("2.11.6", "2.10.5")

resolvers          += "Oracle Repository" at "http://download.oracle.com/maven"  // required for sleepycat

lazy val soundProcessesVersion      = "2.17.0"

lazy val scalaColliderSwingVersion  = "1.25.0"

lazy val lucreSwingVersion          = "0.9.0"

lazy val swingPlusVersion           = "0.2.0"

lazy val intensityVersion           = "1.0.0"

lazy val fileUtilVersion            = "1.1.1"

// ---- test ----

lazy val webLaFVersion              = "1.28"

lazy val lucreSTMVersion            = "2.1.1"

libraryDependencies ++= Seq(
  "de.sciss" %% "soundprocesses-views"    % soundProcessesVersion,
  "de.sciss" %% "scalacolliderswing-core" % scalaColliderSwingVersion,
  "de.sciss" %% "fileutil"                % fileUtilVersion,
  "de.sciss" %% "lucreswing"              % lucreSwingVersion,
  "de.sciss" %% "swingplus"               % swingPlusVersion,
  "de.sciss" %  "intensitypalette"        % intensityVersion,
  "de.sciss" %% "lucrestm-bdb"            % lucreSTMVersion % "test",
  "de.sciss" %  "weblaf"                  % webLaFVersion   % "test"
)

scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-encoding", "utf8", "-Xfuture")

// ---- console ----

initialCommands in console :=
  """import de.sciss.nuages._
    |import de.sciss.numbers.Implicits._
    |""".stripMargin

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

