lazy val baseName        = "Wolkenpumpe"
lazy val baseNameL       = baseName.toLowerCase
lazy val projectVersion  = "3.1.0-SNAPSHOT"
lazy val mimaVersion     = "3.1.0"

lazy val commonSettings = Seq(
  version              := projectVersion,
  organization         := "de.sciss",
  homepage             := Some(url(s"https://git.iem.at/sciss/$baseName")),
  description          := "A Prefuse based visual interface for SoundProcesses, a sound synthesis framework",
  licenses             := Seq("AGPL v3+" -> url( "http://www.gnu.org/licenses/agpl-3.0.txt")),
  scalaVersion         := "2.13.3",
  crossScalaVersions   := Seq("2.13.3", "2.12.12"),
  // resolvers            += "Oracle Repository" at "http://download.oracle.com/maven",  // required for sleepycat
  scalacOptions       ++= Seq(
    "-deprecation", "-unchecked", "-feature", "-encoding", "utf8", "-Xlint:-stars-align,_", "-Xsource:2.13"
  ),
  scalacOptions in (Compile, compile) ++= (if (scala.util.Properties.isJavaAtLeast("9")) Seq("-release", "8") else Nil), // JDK >8 breaks API; skip scala-doc
  scalacOptions        += "-Yrangepos",  // this is needed to extract source code
  updateOptions        := updateOptions.value.withLatestSnapshots(false)
) ++ publishSettings

lazy val deps = new {
  val main = new {
    val fileUtil            = "1.1.5"
    val intensity           = "1.0.2"
    val lucreSwing          = "2.3.0-SNAPSHOT"
    val prefuse             = "1.0.1"
    val scalaCollider       = "2.3.0"
    val scalaColliderSwing  = "2.2.0"
    val scissDSP            = "2.1.0"
    val soundProcesses      = "4.3.0-SNAPSHOT"
    val swingPlus           = "0.5.0"
  }
  val test = new {
    val lucre               = "4.2.0-SNAPSHOT"
    val scalaTest           = "3.2.3"
    val scallop             = "3.5.1"
    val submin              = "0.3.4"
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
      "de.sciss"          %% "lucre-bdb" % deps.test.lucre  % Test,
      "de.sciss"          %  "submin"    % deps.test.submin % Test
    ),
    libraryDependencies += {
      // if (scalaVersion.value == "2.13.0-RC2") {
      //   "com.github.scopt" % "scopt_2.13.0-RC1" % deps.test.scopt % Test
      // } else {
        "org.rogach" %% "scallop" % deps.test.scallop % Test
      // }
    },
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
