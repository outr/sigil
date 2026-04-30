ThisBuild / organization := "com.outr"
ThisBuild / version := "1.0.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.8.3"

ThisBuild / githubOwner := "outr"
ThisBuild / githubRepository := "sigil"

val rapidVersion: String = "2.9.3"

val spiceVersion: String = "1.8.3-SNAPSHOT"

val profigVersion: String = "3.7.0"

val scribeVersion: String = "3.19.0"

val lightdbVersion: String = "4.33.1"

val scalatestVersion: String = "3.2.20"

val scalapassVersion: String = "1.4.0"

val awsS3Version: String = "2.42.18"

val robobrowserVersion: String = "2.3.2-SNAPSHOT"

val lsp4jVersion: String = "0.24.0"

val bsp4jVersion: String = "2.2.0-M2"

val lsp4jDebugVersion: String = "0.24.0"

val striderVersion: String = "1.0.1"

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
  .aggregate(core, secrets, script, mcp, tooling, debug, workflow, browser, benchmark, docs)
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
      "com.outr" %% "spice-openapi" % spiceVersion,
      "com.outr" %% "lightdb-all" % lightdbVersion,
      "org.commonmark" % "commonmark" % "0.27.1",
      "software.amazon.awssdk" % "s3" % awsS3Version exclude ("software.amazon.awssdk", "netty-nio-client"),
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

lazy val tooling = (project in file("tooling"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(docNoLinkWarnings *)
  .settings(
    name := "sigil-tooling",
    libraryDependencies ++= Seq(
      // Eclipse LSP4J — LSP protocol types + JSON-RPC subprocess wiring.
      // Mature (used by Metals, JetBrains, etc.); CompletableFuture-based,
      // adapted to rapid Tasks at the session boundary.
      "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % lsp4jVersion,
      "org.eclipse.lsp4j" % "org.eclipse.lsp4j.jsonrpc" % lsp4jVersion,
      // Build Server Protocol — Java types for sbt / Bloop / Mill build queries.
      // Same JSON-RPC machinery as LSP under the hood.
      "ch.epfl.scala" % "bsp4j" % bsp4jVersion,
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

lazy val debug = (project in file("debug"))
  .dependsOn(core % "compile->compile;test->test", tooling % "compile->compile")
  .settings(docNoLinkWarnings *)
  .settings(
    name := "sigil-debug",
    libraryDependencies ++= Seq(
      // Eclipse LSP4J Debug — Debug Adapter Protocol types + JSON-RPC
      // wiring. Same JSON-RPC infrastructure as lsp4j core, used by
      // VS Code / Eclipse / nvim debug clients. Pairs with the
      // language adapter the agent spawns (sbt's debug adapter,
      // delve for Go, debugpy for Python, etc.).
      "org.eclipse.lsp4j" % "org.eclipse.lsp4j.debug" % lsp4jDebugVersion,
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

lazy val workflow = (project in file("workflow"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(docNoLinkWarnings *)
  .settings(
    name := "sigil-workflow",
    libraryDependencies ++= Seq(
      // Strider — typed embedded workflow engine. Sigil wraps the
      // existing manager and exposes workflow management as an
      // agent-callable tool family on top.
      "com.outr" %% "strider" % striderVersion,
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

lazy val browser = (project in file("browser"))
  .dependsOn(core % "compile->compile;test->test", secrets % "compile->compile;test->test")
  .settings(docNoLinkWarnings *)
  .settings(
    name := "sigil-browser",
    libraryDependencies ++= Seq(
      ("com.outr" %% "robobrowser-cdp" % robobrowserVersion)
        .exclude("com.outr", "spice-client_3")
        .exclude("com.outr", "spice-client-netty_3")
        .exclude("com.outr", "spice-server-undertow_3")
        .exclude("com.outr", "rapid-core_3"),
      "org.scalatest" %% "scalatest" % scalatestVersion % Test,
      "com.outr" %% "rapid-test" % rapidVersion % Test,
      "com.outr" %% "spice-server-undertow" % spiceVersion % Test
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
