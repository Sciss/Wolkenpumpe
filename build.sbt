lazy val baseName        = "Wolkenpumpe"
lazy val baseNameL       = baseName.toLowerCase
lazy val projectVersion  = "2.16.0-SNAPSHOT"
lazy val mimaVersion     = "2.15.0"

name                 := baseName
version              := projectVersion

organization         := "de.sciss"
homepage             := Some(url(s"https://github.com/Sciss/${name.value}"))
description          := "A Prefuse based visual interface for SoundProcesses, a sound synthesis framework"
licenses             := Seq("GPL v2+" -> url( "http://www.gnu.org/licenses/gpl-2.0.txt"))
scalaVersion         := "2.12.2"
crossScalaVersions   := Seq("2.12.2", "2.11.11", "2.10.6")
 
resolvers            += "Oracle Repository" at "http://download.oracle.com/maven"  // required for sleepycat

lazy val soundProcessesVersion      = "3.12.3"
lazy val scalaColliderVersion       = "1.22.4-SNAPSHOT"
lazy val scalaColliderSwingVersion  = "1.33.1"
lazy val prefuseVersion             = "1.0.1"
lazy val lucreSwingVersion          = "1.5.1"
lazy val swingPlusVersion           = "0.2.2"
lazy val intensityVersion           = "1.0.0"
lazy val fileUtilVersion            = "1.1.2"
lazy val scissDSPVersion            = "1.2.3"

// ---- test ----

lazy val subminVersion              = "0.2.1"
lazy val lucreVersion               = "3.4.1-SNAPSHOT"
lazy val scalaTestVersion           = "3.0.3"
lazy val scoptVersion               = "3.6.0"

libraryDependencies ++= Seq(
  "de.sciss"          %% "soundprocesses-views"    % soundProcessesVersion,
  "de.sciss"          %% "soundprocesses-compiler" % soundProcessesVersion,
  "de.sciss"          %% "scalacollider"           % scalaColliderVersion,
  "de.sciss"          %% "scalacolliderswing-core" % scalaColliderSwingVersion,
  "de.sciss"          %  "prefuse-core"            % prefuseVersion,
  "de.sciss"          %% "fileutil"                % fileUtilVersion,
  "de.sciss"          %% "lucreswing"              % lucreSwingVersion,
  "de.sciss"          %% "swingplus"               % swingPlusVersion,
  "de.sciss"          %% "scissdsp"                % scissDSPVersion,
  "de.sciss"          %  "intensitypalette"        % intensityVersion,
  "com.github.scopt"  %% "scopt"                   % scoptVersion,
  "de.sciss"          %% "lucre-bdb"               % lucreVersion       % "test",
  "de.sciss"          %  "submin"                  % subminVersion      % "test",
  "org.scalatest"     %% "scalatest"               % scalaTestVersion   % "test"
)

mimaPreviousArtifacts := Set("de.sciss" %% baseNameL % mimaVersion)

scalacOptions ++= {
  val xs = Seq("-deprecation", "-unchecked", "-feature", "-encoding", "utf8", "-Xfuture")
  val ys = if (scalaVersion.value.startsWith("2.10")) xs else xs :+ "-Xlint:-stars-align,_"  // syntax not supported in Scala 2.10
  ys
}

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
