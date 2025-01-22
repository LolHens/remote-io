lazy val scalaVersions = Seq("3.3.1", "2.13.12", "2.12.18")

ThisBuild / scalaVersion := scalaVersions.head
ThisBuild / versionScheme := Some("early-semver")

lazy val commonSettings: SettingsDefinition = Def.settings(
  organization := "de.lolhens",
  version := {
    val Tag = "refs/tags/(.*)".r
    sys.env.get("CI_VERSION").collect { case Tag(tag) => tag }
      .getOrElse("0.0.1-SNAPSHOT")
  },

  licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0")),

  homepage := scmInfo.value.map(_.browseUrl),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/LolHens/remote-io"),
      "scm:git@github.com:LolHens/remote-io.git"
    )
  ),
  developers := List(
    Developer(id = "LolHens", name = "Pierre Kisters", email = "pierrekisters@gmail.com", url = url("https://github.com/LolHens/"))
  ),

  libraryDependencies ++= Seq(
    "ch.qos.logback" % "logback-classic" % "1.4.13" % Test,
    "de.lolhens" %%% "munit-tagless-final" % "0.2.0" % Test,
    "org.scalameta" %%% "munit" % "1.1.0" % Test,
  ),

  testFrameworks += new TestFramework("munit.Framework"),

  libraryDependencies ++= virtualAxes.?.value.getOrElse(Seq.empty).collectFirst {
    case VirtualAxis.ScalaVersionAxis(version, _) if version.startsWith("2.") =>
      compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
  },

  Compile / doc / sources := Seq.empty,

  publishMavenStyle := true,

  publishTo := sonatypePublishToBundle.value,

  credentials ++= (for {
    username <- sys.env.get("SONATYPE_USERNAME")
    password <- sys.env.get("SONATYPE_PASSWORD")
  } yield Credentials(
    "Sonatype Nexus Repository Manager",
    "oss.sonatype.org",
    username,
    password
  )).toList
)

name := (core.projectRefs.head / name).value

val V = new {
  val catsEffect = "3.5.2"
  val http4s = "0.23.24"
  val sourcecode = "0.3.1"
}

lazy val root: Project =
  project
    .in(file("."))
    .settings(commonSettings)
    .settings(
      publishArtifact := false,
      publish / skip := true
    )
    .aggregate(core.projectRefs: _*)
    .aggregate(http4s.projectRefs: _*)

lazy val core = projectMatrix.in(file("core"))
  .settings(commonSettings)
  .settings(
    name := "remote-io-core",
  )
  .jvmPlatform(scalaVersions)
  .jsPlatform(scalaVersions)

lazy val http4s = projectMatrix.in(file("http4s"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "remote-io-http4s",

    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect" % V.catsEffect,
      "org.http4s" %%% "http4s-client" % V.http4s,
      "com.lihaoyi" %%% "sourcecode" % V.sourcecode,
    ),
  )
  .jvmPlatform(scalaVersions)
  .jsPlatform(scalaVersions)
