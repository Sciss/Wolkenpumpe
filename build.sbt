lazy val baseName        = "Wolkenpumpe"
lazy val baseNameL       = baseName.toLowerCase
lazy val projectVersion  = "3.5.0"
lazy val mimaVersion     = "3.5.0"

ThisBuild / version       := projectVersion
ThisBuild / organization  := "de.sciss"
ThisBuild / versionScheme := Some("pvp")

lazy val commonSettings = Seq(
  homepage             := Some(url(s"https://git.iem.at/sciss/$baseName")),
  description          := "A Prefuse based visual interface for SoundProcesses, a sound synthesis framework",
  licenses             := Seq("AGPL v3+" -> url( "http://www.gnu.org/licenses/agpl-3.0.txt")),
  scalaVersion         := "2.13.6",
  crossScalaVersions   := Seq("3.0.0", "2.13.6", "2.12.14"),
  scalacOptions       ++= Seq(
    "-deprecation", "-unchecked", "-feature", "-encoding", "utf8", "-Xlint:-stars-align,_", "-Xsource:2.13"
  ),
  Compile / compile / scalacOptions ++= (if (scala.util.Properties.isJavaAtLeast("9")) Seq("-release", "8") else Nil), // JDK >8 breaks API; skip scala-doc
  scalacOptions        += "-Yrangepos",  // this is needed to extract source code
  updateOptions        := updateOptions.value.withLatestSnapshots(false),
//  Compile / doc / sources := {
//    if (isDotty.value) Nil else (sources in (Compile, doc)).value // dottydoc is pretty much broken
//  },
) ++ publishSettings

lazy val deps = new {
  val main = new {
    val fileUtil            = "1.1.5"
    val intensity           = "1.0.2"
    val lucreSwing          = "2.6.3"
    val prefuse             = "1.0.1"
    val scalaCollider       = "2.6.4"
    val scalaColliderSwing  = "2.6.4"
    val scissDSP            = "2.2.2"
    val soundProcesses      = "4.8.0"
    val swingPlus           = "0.5.0"
  }
  val test = new {
    val lucre               = "4.4.5"
    val scalaTest           = "3.2.9"
    val scallop             = "4.0.3"
    val submin              = "0.3.4"
  }
}

lazy val root = project.withId(baseNameL).in(file("."))
  .aggregate(core, basic)
  .dependsOn(core, basic)
  .settings(commonSettings)
  .settings(
    name := baseName,
    Compile / packageBin / publishArtifact := false, // there are no binaries
    Compile / packageDoc / publishArtifact := false, // there are no javadocs
    Compile / packageSrc / publishArtifact := false, // there are no sources
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
      "de.sciss"          %% "lucre-swing"             % deps.main.lucreSwing,
      "de.sciss"          %% "swingplus"               % deps.main.swingPlus,
      "de.sciss"          %% "scissdsp"                % deps.main.scissDSP,
      "de.sciss"          %  "intensitypalette"        % deps.main.intensity,
      "de.sciss"          %% "lucre-bdb"               % deps.test.lucre     % Test
    ),
    libraryDependencies += {
      "org.scalatest" %% "scalatest" % deps.test.scalaTest % Test
    },
    mimaPreviousArtifacts := Set("de.sciss" %% s"$baseNameL-core" % mimaVersion),
    console / initialCommands :=
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
      "de.sciss"    %% "lucre-bdb" % deps.test.lucre   % Test,
      "de.sciss"    %  "submin"    % deps.test.submin  % Test,
      "org.rogach"  %% "scallop"   % deps.test.scallop % Test,
    ),
    mimaPreviousArtifacts := Set("de.sciss" %% s"$baseNameL-basic" % mimaVersion),
  )

// ---- publishing ----

lazy val publishSettings = Seq(
  publishMavenStyle := true,
  Test / publishArtifact := false,
  pomIncludeRepository := { _ => false },
  developers := List(
    Developer(
      id    = "sciss",
      name  = "Hanns Holger Rutz",
      email = "contact@sciss.de",
      url   = url("https://www.sciss.de")
    )
  ),
  scmInfo := {
    val h = "github.com"
    val a = s"Sciss/$baseName"
    Some(ScmInfo(url(s"https://$h/$a"), s"scm:git@$h:$a.git"))
  },
)
