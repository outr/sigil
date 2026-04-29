ThisBuild / organization := "com.outr"
ThisBuild / version := "1.0.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.8.3"

ThisBuild / githubOwner := "outr"
ThisBuild / githubRepository := "sigil"

val rapidVersion: String = "2.9.3"

val spiceVersion: String = "1.8.2"

val profigVersion: String = "3.7.0"

val scribeVersion: String = "3.19.0"

val lightdbVersion: String = "4.33.1"

val scalatestVersion: String = "3.2.20"

val scalapassVersion: String = "1.4.0"

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

// Scaladoc 3's `[[ ]]` link resolver doesn't follow imports the way
// older scaladoc did — it warns on every short-name link to a type
// that's imported but not fully qualified. Sigil has hundreds of such
// links across signals, events, transports, etc.; fully qualifying
// them all is a heavy edit with no real benefit (the rendered HTML
// links still break, the warning is the only artifact). Pass scaladoc's
// `-no-link-warnings` flag to silence them so `publishLocal` and
// `Compile/doc` produce clean output. Real code warnings still surface.
val docNoLinkWarnings: Seq[Setting[?]] = Seq(
  Compile / doc / scalacOptions += "-no-link-warnings"
)

lazy val root = (project in file("."))
  .aggregate(core, secrets, script, mcp, benchmark, docs)
  .settings(
    name := "sigil",
    publish / skip := true
  )

lazy val core = (project in file("core"))
  .settings(docNoLinkWarnings *)
  .settings(
    name := "sigil-core",
    libraryDependencies ++= Seq(
      "com.outr" %% "profig" % profigVersion,
      "com.outr" %% "scribe" % scribeVersion,
      "com.outr" %% "scribe-file" % scribeVersion,
      "com.outr" %% "spice-api" % spiceVersion,
      "com.outr" %% "spice-client-netty" % spiceVersion,
      "com.outr" %% "spice-server" % spiceVersion,
      "com.outr" %% "lightdb-all" % lightdbVersion,
      "org.commonmark" % "commonmark" % "0.27.0",
      "org.scalatest" %% "scalatest" % scalatestVersion % Test,
      "com.outr" %% "rapid-test" % rapidVersion % Test,
      "com.outr" %% "spice-server-undertow" % spiceVersion % Test
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

lazy val secrets = (project in file("secrets"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(docNoLinkWarnings *)
  .settings(
    name := "sigil-secrets",
    libraryDependencies ++= Seq(
      "com.outr" %% "scalapass" % scalapassVersion,
      "org.scalatest" %% "scalatest" % scalatestVersion % Test,
      "com.outr" %% "rapid-test" % rapidVersion % Test
    ),
    fork := true,
    Test / parallelExecution := false,
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
    Test / testGrouping := (Test / definedTests).value.map { test =>
      Tests.Group(
        name = test.name,
        tests = Seq(test),
        runPolicy = Tests.SubProcess(ForkOptions())
      )
    }
  )

lazy val script = (project in file("script"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(docNoLinkWarnings *)
  .settings(
    name := "sigil-script",
    libraryDependencies ++= Seq(
      // dotty.tools.repl.ScriptEngine — the heavy dep that justifies a separate sub-project.
      // Apps that don't need arbitrary-code execution don't pay this cost.
      "org.scala-lang" %% "scala3-repl" % scalaVersion.value,
      "org.scalatest" %% "scalatest" % scalatestVersion % Test,
      "com.outr" %% "rapid-test" % rapidVersion % Test
    ),
    fork := true,
    Test / parallelExecution := false,
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
    Test / testGrouping := (Test / definedTests).value.map { test =>
      Tests.Group(
        name = test.name,
        tests = Seq(test),
        runPolicy = Tests.SubProcess(ForkOptions())
      )
    }
  )

lazy val mcp = (project in file("mcp"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(docNoLinkWarnings *)
  .settings(
    name := "sigil-mcp",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % scalatestVersion % Test,
      "com.outr" %% "rapid-test" % rapidVersion % Test
    ),
    fork := true,
    Test / parallelExecution := false,
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
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
