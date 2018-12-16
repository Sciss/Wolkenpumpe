lazy val baseName        = "Wolkenpumpe"
lazy val baseNameL       = baseName.toLowerCase
lazy val projectVersion  = "2.29.0-SNAPSHOT"
lazy val mimaVersion     = "2.29.0"

lazy val commonSettings = Seq(
  version              := projectVersion,
  organization         := "de.sciss",
  homepage             := Some(url(s"https://git.iem.at/sciss/$baseName")),
  description          := "A Prefuse based visual interface for SoundProcesses, a sound synthesis framework",
  licenses             := Seq("GPL v2+" -> url( "http://www.gnu.org/licenses/gpl-2.0.txt")),
  scalaVersion         := "2.13.0-M5",
  crossScalaVersions   := Seq("2.12.8", "2.11.12", "2.13.0-M5"),
  resolvers            += "Oracle Repository" at "http://download.oracle.com/maven",  // required for sleepycat
  scalacOptions       ++= Seq(
    "-deprecation", "-unchecked", "-feature", "-encoding", "utf8", "-Xfuture", "-Xlint:-stars-align,_", "-Xsource:2.13"
  ),
  scalacOptions        += "-Yrangepos",  // this is needed to extract source code
  updateOptions        := updateOptions.value.withLatestSnapshots(false)
) ++ publishSettings

lazy val deps = new {
  val main = new {
    val soundProcesses      = "3.24.0-SNAPSHOT"
    val scalaCollider       = "1.28.0-SNAPSHOT"
    val scalaColliderSwing  = "1.41.0-SNAPSHOT"
    val prefuse             = "1.0.1"
    val lucreSwing          = "1.14.0-SNAPSHOT"
    val swingPlus           = "0.4.0-SNAPSHOT"
    val intensity           = "1.0.0"
    val fileUtil            = "1.1.3"
    val scissDSP            = "1.3.1-SNAPSHOT"
  }
  val test = new {
    val submin              = "0.2.3"
    val lucre               = "3.11.0-SNAPSHOT"
    val scalaTest           = "3.0.5"
    val scopt               = "3.7.1"
  }
}

lazy val root = project.withId(baseNameL).in(file("."))
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

lazy val core = project.withId(s"$baseNameL-core").in(file("core"))
  .settings(commonSettings)
  .settings(
    name := s"$baseName-Core",
    libraryDependencies ++= Seq(
      "de.sciss"          %% "soundprocesses-views"    % deps.main.soundProcesses,
      "de.sciss"          %% "soundprocesses-compiler" % deps.main.soundProcesses,
      "de.sciss"          %% "scalacollider"           % deps.main.scalaCollider,
      "de.sciss"          %% "scalacolliderswing-core" % deps.main.scalaColliderSwing,
      "de.sciss"          %  "prefuse-core"            % deps.main.prefuse,
      "de.sciss"          %% "fileutil"                % deps.main.fileUtil,
      "de.sciss"          %% "lucreswing"              % deps.main.lucreSwing,
      "de.sciss"          %% "swingplus"               % deps.main.swingPlus,
      "de.sciss"          %% "scissdsp"                % deps.main.scissDSP,
      "de.sciss"          %  "intensitypalette"        % deps.main.intensity,
      "de.sciss"          %% "lucre-bdb"               % deps.test.lucre  % Test,
    ),
    libraryDependencies += {
      val v = if (scalaVersion.value == "2.13.0-M5") "3.0.6-SNAP5" else deps.test.scalaTest
      "org.scalatest" %% "scalatest" % v % Test
    },
    mimaPreviousArtifacts := Set("de.sciss" %% s"$baseNameL-core" % mimaVersion),
    initialCommands in console :=
      """import de.sciss.nuages._
        |import de.sciss.numbers.Implicits._
        |""".stripMargin
  )

lazy val basic = project.withId(s"$baseNameL-basic").in(file("basic"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := s"$baseName-Basic",
    libraryDependencies ++= Seq(
      "com.github.scopt"  %% "scopt"     % deps.test.scopt  % Test,
      "de.sciss"          %% "lucre-bdb" % deps.test.lucre  % Test,
      "de.sciss"          %  "submin"    % deps.test.submin % Test
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
  <url>git@git.iem.at:sciss/{n}.git</url>
  <connection>scm:git:git@git.iem.at:sciss/{n}.git</connection>
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
