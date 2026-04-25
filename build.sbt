ThisBuild / organization := "com.outr"
ThisBuild / version := "1.0.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.8.3"

ThisBuild / githubOwner := "outr"
ThisBuild / githubRepository := "sigil"

val rapidVersion: String = "2.9.2"

val spiceVersion: String = "1.7.0"

val profigVersion: String = "3.7.0"

val scribeVersion: String = "3.19.0"

val lightdbVersion: String = "4.31.0"

val scalatestVersion: String = "3.2.20"

ThisBuild / versionScheme := Some("early-semver")
ThisBuild / licenses := Seq("MIT" -> url("https://github.com/outr/sigil/blob/master/LICENSE"))
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xmax-inlines",
  "64"
)

ThisBuild / evictionErrorLevel := Level.Info

lazy val root = (project in file("."))
  .aggregate(core, benchmark, docs)
  .settings(
    name := "sigil",
    publish / skip := true
  )

lazy val core = (project in file("core"))
  .settings(
    name := "sigil-core",
    libraryDependencies ++= Seq(
      "com.outr" %% "profig" % profigVersion,
      "com.outr" %% "scribe" % scribeVersion,
      "com.outr" %% "scribe-file" % scribeVersion,
      "com.outr" %% "spice-api" % spiceVersion,
      "com.outr" %% "spice-client-netty" % spiceVersion,
      "com.outr" %% "lightdb-all" % lightdbVersion,
      "org.scalatest" %% "scalatest" % scalatestVersion % Test,
      "com.outr" %% "rapid-test" % rapidVersion % Test
    ),
    fork := true,
    Test / parallelExecution := false,
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
    // One forked JVM per test suite — gives each spec a clean process so init-once
    // state (singletons, PolyType registrations, RocksDB locks) is exercised
    // fresh on every run. Combined with parallelExecution := false above, suites
    // run serially so they take turns acquiring the RocksDB lock.
    Test / testGrouping := (Test / definedTests).value.map { test =>
      Tests.Group(
        name = test.name,
        tests = Seq(test),
        runPolicy = Tests.SubProcess(ForkOptions())
      )
    }
  )

lazy val benchmark = project
  .in(file("benchmark"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    name := "sigil-benchmark",
    publish / skip := true,
    fork := true,
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % scalatestVersion % Test,
      "com.outr" %% "rapid-test" % rapidVersion % Test
    )
  )

lazy val docs = project
  .in(file("documentation"))
  .dependsOn(core)
  .enablePlugins(MdocPlugin)
  .settings(
    publish / skip := true,
    mdocVariables := Map(
      "VERSION" -> version.value
    ),
    mdocOut := file(".")
  )
