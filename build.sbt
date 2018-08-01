lazy val baseName        = "Wolkenpumpe"
lazy val baseNameL       = baseName.toLowerCase
lazy val projectVersion  = "2.26.0"
lazy val mimaVersion     = "2.26.0"

lazy val commonSettings = Seq(
  version              := projectVersion,
  organization         := "de.sciss",
  homepage             := Some(url(s"https://github.com/Sciss/$baseName")),
  description          := "A Prefuse based visual interface for SoundProcesses, a sound synthesis framework",
  licenses             := Seq("GPL v2+" -> url( "http://www.gnu.org/licenses/gpl-2.0.txt")),
  scalaVersion         := "2.12.6",
  crossScalaVersions   := Seq("2.12.6", "2.11.12"),
  resolvers            += "Oracle Repository" at "http://download.oracle.com/maven",  // required for sleepycat
  scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-encoding", "utf8", "-Xfuture", "-Xlint:-stars-align,_"),
  scalacOptions        += "-Yrangepos"  // this is needed to extract source code
) ++ publishSettings

lazy val deps = new {
  val main = new {
    val soundProcesses      = "3.21.0"
    val scalaCollider       = "1.27.0"
    val scalaColliderSwing  = "1.39.0"
    val prefuse             = "1.0.1"
    val lucreSwing          = "1.11.0"
    val swingPlus           = "0.3.1"
    val intensity           = "1.0.0"
    val model               = "0.3.4"
    val fileUtil            = "1.1.3"
    val scissDSP            = "1.3.0"
  }
  val test = new {
    val submin              = "0.2.2"
    val lucre               = "3.9.0"
    val scalaTest           = "3.0.5"
    val scopt               = "3.7.0"
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
      "de.sciss"          %% "model"                   % deps.main.model,    // (sbt bug)
      "de.sciss"          %% "lucre-bdb"               % deps.test.lucre     % "test",
      "org.scalatest"     %% "scalatest"               % deps.test.scalaTest % "test"
    ),
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
      "com.github.scopt"  %% "scopt"     % deps.test.scopt  % "test",
      "de.sciss"          %% "lucre-bdb" % deps.test.lucre  % "test",
      "de.sciss"          %  "submin"    % deps.test.submin % "test"
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
