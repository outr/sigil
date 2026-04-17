name := "sigil"
organization := "com.outr"
version := "1.0.0-SNAPSHOT"

scalaVersion := "3.8.3"

githubOwner := "outr"
githubRepository := "sigil"

val rapidVersion: String = "2.9.2"

val spiceVersion: String = "1.6.0"

val profigVersion: String = "3.6.0"

val scribeVersion: String = "3.19.0"

val lightdbVersion: String = "4.30.0"

val scalatestVersion: String = "3.2.20"

ThisBuild / versionScheme := Some("early-semver")
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xmax-inlines", "64"
)

ThisBuild / evictionErrorLevel := Level.Info

Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF")

libraryDependencies ++= Seq(
  "com.outr" %% "profig" % profigVersion,
  "com.outr" %% "scribe" % scribeVersion,
  "com.outr" %% "scribe-file" % scribeVersion,
  "com.outr" %% "spice-api" % spiceVersion,
  "com.outr" %% "spice-client-netty" % spiceVersion,
  "com.outr" %% "lightdb-all" % lightdbVersion,
  "org.scalatest" %% "scalatest" % scalatestVersion % Test,
  "com.outr" %% "rapid-test" % rapidVersion % Test
)

fork := true
Test / parallelExecution := false