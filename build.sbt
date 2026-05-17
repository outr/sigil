ThisBuild / organization := "com.outr"
ThisBuild / version := "1.0.0-SNAPSHOT9"

ThisBuild / scalaVersion := "3.8.3"

val rapidVersion: String = "2.9.4"

val spiceVersion: String = "1.8.8"

val profigVersion: String = "3.7.1"

val scribeVersion: String = "3.19.0"

val lightdbVersion: String = "4.36.1"

val striderVersion: String = "1.0.4"

val scalapassVersion: String = "1.4.1"

val awsS3Version: String = "2.44.4"

val robobrowserVersion: String = "2.3.2"

val commonmarkVersion: String = "0.28.0"

val lsp4jVersion: String = "1.0.0"

val bsp4jVersion: String = "2.2.0-M4.TEST"

val lsp4jDebugVersion: String = "1.0.0"

val jtokkitVersion: String = "1.1.0"

val scalatestVersion: String = "3.2.20"

ThisBuild / versionScheme := Some("early-semver")
ThisBuild / licenses := Seq("MIT" -> url("https://github.com/outr/sigil/blob/master/LICENSE"))
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked"
)
ThisBuild / javaOptions ++= Seq("-Xmx16G", "-Xss4m", "-XX:MaxMetaspaceSize=2g")

// Tests run with one forked JVM per spec (see `testGrouping` in each
// subproject) and each spec writes to its own RocksDB directory
// (`db/test/<SimpleClassName>` via `TestSigil.initFor`). The per-suite
// fork already isolates `PolyType` registrations and the RocksDB lock,
// so we run forks in parallel — capped at 4 concurrent JVMs so memory
// pressure stays sane and live-LLM specs (LlamaCpp*Spec) don't pile
// onto the shared upstream server faster than it can serve them.
Global / concurrentRestrictions := Seq(
  Tags.limit(Tags.ForkedTestGroup, 4),
  Tags.limit(Tags.Test, 4)
)

ThisBuild / evictionErrorLevel := Level.Info

// sbt 1.12.x's `lintUnused` check flags `Compile / doc / fork` +
// `Compile / doc / javaOptions` as "unused" because they don't
// participate in any auto-running task — they're consumed only
// when `publishLocal` triggers `doc`. The keys ARE used; sbt's
// lint just can't trace the dependency. Silence the noise without
// disabling the lint globally.
Global / excludeLintKeys ++= Set(
  Compile / doc / fork,
  Compile / doc / javaOptions
)

// Scaladoc 3's `[[ ]]` link resolver doesn't follow imports the way
// older scaladoc did — it warns on every short-name link to a type
// that's imported but not fully qualified. Sigil has hundreds of such
// links across signals, events, transports, etc.; fully qualifying
// them all is a heavy edit with no real benefit (the rendered HTML
// links still break, the warning is the only artifact). Pass scaladoc's
// `-no-link-warnings` flag to silence them so `publishLocal` and
// `Compile/doc` produce clean output. Real code warnings still surface.
//
// Scala 3 scaladoc forks its own JVM that doesn't inherit
// `ThisBuild / javaOptions`. `publishLocal` triggers `doc` per
// aggregated module — give the fork a real heap so a large module
// (`core`, `browser`) doesn't OOM mid-doc with the default 1g.
val docNoLinkWarnings: Seq[Setting[?]] = Seq(
  Compile / doc / scalacOptions += "-no-link-warnings",
  Compile / doc / fork := true,
  Compile / doc / javaOptions ++= Seq("-Xmx8g", "-Xss4m", "-XX:MaxMetaspaceSize=2g")
)

lazy val root = (project in file("."))
  .aggregate(core, secrets, script, mcp, tooling, metals, debug, browser, all, benchmark, docs)
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
      // Strider — typed embedded workflow engine. Was a separate
      // sub-project (`sigil-workflow`); folded into core because
      // worker delegation is built on top of workflow runs and
      // we want it available without a mixin.
      "com.outr" %% "strider" % striderVersion,
      "org.commonmark" % "commonmark" % commonmarkVersion,
      "software.amazon.awssdk" % "s3" % awsS3Version exclude ("software.amazon.awssdk", "netty-nio-client"),
      // Pure-Java port of OpenAI's tiktoken — used by `sigil.tokenize.JtokkitTokenizer`
      // for accurate token counts when validating that wire requests fit the model's
      // context window. Decent approximation for non-OpenAI providers too.
      "com.knuddels" % "jtokkit" % jtokkitVersion,
      "org.scalatest" %% "scalatest" % scalatestVersion % Test,
      "com.outr" %% "rapid-test" % rapidVersion % Test,
      "com.outr" %% "spice-server-undertow" % spiceVersion % Test
    ),
    fork := true,
    Test / parallelExecution := true,
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
    // One forked JVM per test suite — gives each spec a clean process so
    // init-once state (singletons, PolyType registrations, RocksDB locks) is
    // exercised fresh on every run. Forks run concurrently (capped at the
    // global `Tags.ForkedTestGroup` limit); each spec uses its own RocksDB
    // path under `db/test/<SimpleClassName>` so there's no lock contention.
    // ForkOptions().withEnvVars(sys.env) forwards the parent shell's env
    // vars so SIGIL_LLAMACPP_HOST etc. reach the forked JVM.
    Test / testGrouping := (Test / definedTests).value.map { test =>
      Tests.Group(
        name = test.name,
        tests = Seq(test),
        runPolicy = Tests.SubProcess(ForkOptions().withEnvVars(sys.env))
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
    Test / parallelExecution := true,
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
    Test / parallelExecution := true,
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
    Test / parallelExecution := true,
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
    Test / testGrouping := (Test / definedTests).value.map { test =>
      Tests.Group(
        name = test.name,
        tests = Seq(test),
        runPolicy = Tests.SubProcess(ForkOptions())
      )
    }
  )

lazy val metals = (project in file("metals"))
  .dependsOn(core % "compile->compile;test->test", mcp, tooling)
  .settings(docNoLinkWarnings *)
  .settings(
    name := "sigil-metals",
    libraryDependencies ++= Seq(
      // LSP4J — Metals is an LSP server; we drive the handshake +
      // auto-respond to `window/showMessageRequest` (sigil bug #70)
      // and route `window/logMessage` into ToolLog events (#69).
      "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % lsp4jVersion,
      "org.eclipse.lsp4j" % "org.eclipse.lsp4j.jsonrpc" % lsp4jVersion,
      "org.scalatest" %% "scalatest" % scalatestVersion % Test,
      "com.outr" %% "rapid-test" % rapidVersion % Test
    ),
    fork := true,
    Test / parallelExecution := true,
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
    Test / parallelExecution := true,
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
    Test / parallelExecution := true,
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
    Test / testGrouping := (Test / definedTests).value.map { test =>
      Tests.Group(
        name = test.name,
        tests = Seq(test),
        runPolicy = Tests.SubProcess(ForkOptions())
      )
    }
  )

/**
 * Aggregator artifact — depends on every published Sigil module so a downstream
 * consumer can pull in the whole framework with one `"com.outr" %% "sigil-all" %
 * version` line. No source of its own; the POM carries the transitive deps.
 */
lazy val all = (project in file("all"))
  .dependsOn(core, secrets, script, mcp, metals, tooling, debug, browser)
  .settings(docNoLinkWarnings *)
  .settings(
    name := "sigil-all",
    // Bug #76 belt-and-suspenders: re-declare runtime libs whose transitive
    // resolution from `sigil-core` is unreliable across resolvers. The
    // `sigil-all` POM lists each at the top level so coursier / Maven /
    // Ivy / Gradle / Mill all pick them up regardless of how they follow
    // `compile->default(runtime)` mappings. The same pattern catches future
    // cases (a tokenizer / parser dep added to sigil-script, etc.).
    libraryDependencies ++= Seq(
      "com.knuddels" % "jtokkit" % jtokkitVersion
    )
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
    Test / parallelExecution := true,
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
