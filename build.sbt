lazy val baseName        = "Wolkenpumpe"
lazy val baseNameL       = baseName.toLowerCase
lazy val projectVersion  = "2.20.0-SNAPSHOT"
lazy val mimaVersion     = "2.20.0"

lazy val commonSettings = Seq(
  version              := projectVersion,
  organization         := "de.sciss",
  homepage             := Some(url(s"https://github.com/Sciss/$baseName")),
  description          := "A Prefuse based visual interface for SoundProcesses, a sound synthesis framework",
  licenses             := Seq("GPL v2+" -> url( "http://www.gnu.org/licenses/gpl-2.0.txt")),
  scalaVersion         := "2.12.4",
  crossScalaVersions   := Seq("2.12.4", "2.11.11"),
  resolvers            += "Oracle Repository" at "http://download.oracle.com/maven",  // required for sleepycat
  scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-encoding", "utf8", "-Xfuture", "-Xlint:-stars-align,_")
) ++ publishSettings

lazy val soundProcessesVersion      = "3.16.0"
lazy val scalaColliderVersion       = "1.23.0"
lazy val scalaColliderSwingVersion  = "1.35.0"
lazy val prefuseVersion             = "1.0.1"
lazy val lucreSwingVersion          = "1.7.0"
lazy val swingPlusVersion           = "0.2.4"
lazy val intensityVersion           = "1.0.0"
lazy val modelVersion               = "0.3.4"
lazy val fileUtilVersion            = "1.1.3"
lazy val scissDSPVersion            = "1.2.3"

// ---- test ----

lazy val subminVersion              = "0.2.2"
lazy val lucreVersion               = "3.5.0"
lazy val scalaTestVersion           = "3.0.4"
lazy val scoptVersion               = "3.7.0"

lazy val root = Project(id = baseNameL, base = file("."))
  .aggregate(core, basic)
  .dependsOn(core, basic)
  .settings(commonSettings)
  .settings(
    name := baseName,
    publishArtifact in(Compile, packageBin) := false, // there are no binaries
    publishArtifact in(Compile, packageDoc) := false, // there are no javadocs
    publishArtifact in(Compile, packageSrc) := false, // there are no sources
    autoScalaLibrary := false
  )

lazy val core = Project(id = s"$baseNameL-core", base = file("core"))
  .settings(commonSettings)
  .settings(
    name := s"$baseName-Core",
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
      "de.sciss"          %% "model"                   % modelVersion,    // bloody sbt buf
      "de.sciss"          %% "lucre-bdb"               % lucreVersion     % "test",
      "org.scalatest"     %% "scalatest"               % scalaTestVersion % "test"
    ),
    mimaPreviousArtifacts := Set("de.sciss" %% s"$baseNameL-core" % mimaVersion),
    initialCommands in console :=
      """import de.sciss.nuages._
        |import de.sciss.numbers.Implicits._
        |""".stripMargin
  )

lazy val basic = Project(id = s"$baseNameL-basic", base = file("basic"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := s"$baseName-Basic",
    libraryDependencies ++= Seq(
      "com.github.scopt"  %% "scopt"     % scoptVersion  % "test",
      "de.sciss"          %% "lucre-bdb" % lucreVersion  % "test",
      "de.sciss"          %  "submin"    % subminVersion % "test"
    ),
    mimaPreviousArtifacts := Set("de.sciss" %% s"$baseNameL-basic" % mimaVersion)
  )

// ---- publishing ----

lazy val publishSettings = Seq(
  publishMavenStyle := true,
  publishTo := {
    Some(if (isSnapshot.value)
      "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
    else
      "Sonatype Releases"  at "https://oss.sonatype.org/service/local/staging/deploy/maven2"
    )
  },
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false },
  pomExtra := { val n = baseName
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
)
