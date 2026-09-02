ThisBuild / organization := "com.outr"
ThisBuild / version := "1.4.0-SNAPSHOT2"

ThisBuild / scalaVersion := "3.8.4"

val rapidVersion: String = "2.9.9"

val spiceVersion: String = "1.10.9-SNAPSHOT"

val profigVersion: String = "3.8.0"

val scribeVersion: String = "3.19.0"

val lightdbVersion: String = "4.48.0-SNAPSHOT"

val striderVersion: String = "1.1.6"

val scalapassVersion: String = "1.4.2"

val awsS3Version: String = "2.54.10"

// 2.4.0 adds `RoboBrowserConfig.virtualDisplay` (Xvfb-backed headful sessions)
// and the `robobrowser-stream` artifact `sigil-browser-stream` wraps. The
// `browser` module tracks the same version so cdp doesn't split across the two.
val robobrowserVersion: String = "2.4.0-SNAPSHOT"

val commonmarkVersion: String = "0.29.0"

val lsp4jVersion: String = "1.0.0"

val bsp4jVersion: String = "2.2.0-M4.TEST"

val lsp4jDebugVersion: String = "1.0.0"

val jtokkitVersion: String = "1.1.0"

val twelveMonkeysVersion: String = "3.13.1"

val scalatestVersion: String = "3.2.20"

// Force rapid-core to the version with Stream.handleErrorWith (#399) — it
// otherwise arrives transitively (spice / lightdb / strider) at the older
// release. Additive/binary-compatible, so overriding is safe.
ThisBuild / dependencyOverrides += "com.outr" %% "rapid-core" % rapidVersion

ThisBuild / versionScheme := Some("early-semver")
ThisBuild / licenses := Seq("MIT" -> url("https://github.com/outr/sigil/blob/master/LICENSE"))

ThisBuild / resolvers := Seq(
  Resolver.githubPackages("outr")
)

ThisBuild / githubOwner := "outr"
ThisBuild / githubRepository := "sigil"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  // fabric's `RW.gen` inlines once per field; the widest records here
  // (ContextMemory) exceed the 32 default.
  "-Xmax-inlines",
  "64"
)
// Per-forked-JVM heap. Local default is generous; CI (a 4-vCPU / 16 GB
// runner driving several concurrent forks) overrides via env so the
// forks don't over-commit the box.
ThisBuild / javaOptions ++= Seq(
  s"-Xmx${sys.env.getOrElse("SIGIL_TEST_FORK_HEAP", "16G")}",
  "-Xss4m",
  "-XX:MaxMetaspaceSize=2g"
)

// Concurrent forked-test JVMs. Wall-clock on `sbt test` is dominated by
// per-suite fork overhead (JVM boot + RocksDB/Lucene init across ~500
// suites), which is init-I/O heavy rather than CPU-bound — modest
// oversubscription of the core count helps. Env-tunable so CI can pick
// the value matching its runner shape.
val testForkConcurrency: Int =
  sys.env.get("SIGIL_TEST_FORK_CONCURRENCY")
    .flatMap(v => scala.util.Try(v.toInt).toOption)
    .getOrElse(4)

Global / concurrentRestrictions := Seq(
  Tags.limit(Tags.ForkedTestGroup, testForkConcurrency),
  Tags.limit(Tags.Test, testForkConcurrency)
)

// Smoke mode (SIGIL_TEST_SMOKE=1): `sbt test` still COMPILES every
// module's main + test sources (the highest-signal CI check) but
// executes only the allow-listed suites below — fast, deterministic,
// cross-cutting framework invariants. The full ~500-suite battery runs
// locally via test-all.sh and on CI's weekly schedule /
// workflow_dispatch(full); per-suite fork overhead (JVM boot +
// RocksDB/Lucene init) dominates full-run wall clock, so skipping suite
// EXECUTION is where the time goes.
val smokeSuites: Set[String] = Set(
  // Wiring + registration invariants
  "spec.SigilWiringSpec",
  "spec.PolymorphicRegistrationOrderSpec",
  // Source lints
  "spec.ToolDescriptionAuditSpec",
  "spec.ToolInputErgonomicsAuditSpec",
  // Wire / caching pins
  "spec.VolatileContextTailPlacementSpec",
  "spec.AnthropicPromptCachingSpec",
  "spec.RespondEndsTurnWordingSpec",
  // Agent-loop contracts
  "spec.NakedTextTerminalSpec",
  "spec.StopContractSpec",
  "spec.ContextOverflowRecoverySpec",
  "spec.PostRespondContextSpec",
  "spec.TurnScopedReadDedupSpec",
  // Curator / budget contracts
  "spec.CuratorBudgetTokenizerSpec",
  "spec.ActiveTurnElisionProtectionSpec",
  // One fast, no-external-process representative per opt-in module
  "spec.BspCompileErrorCauseSpec",
  "spec.BrowserNavigationGuardSpec"
)

ThisBuild / Test / testOptions ++= {
  if (sys.env.get("SIGIL_TEST_SMOKE").exists(_.nonEmpty))
    Seq(Tests.Filter(smokeSuites.contains))
  else Seq.empty
}

ThisBuild / evictionErrorLevel := Level.Info

Global / excludeLintKeys ++= Set(
  Compile / doc / fork,
  Compile / doc / javaOptions
)

val docNoLinkWarnings: Seq[Setting[?]] = Seq(
  Compile / doc / scalacOptions += "-no-link-warnings",
  Compile / doc / fork := true,
  Compile / doc / javaOptions ++= Seq("-Xmx8g", "-Xss4m", "-XX:MaxMetaspaceSize=2g")
)

lazy val root = (project in file("."))
  .aggregate(core, secrets, script, mcp, tooling, metals, debug, browser, streamBrowser, all, benchmark, docs)
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
      "com.outr" %% "spice-server-undertow" % spiceVersion,
      "com.outr" %% "spice-openapi" % spiceVersion,
      "com.outr" %% "lightdb-all" % lightdbVersion,
      "com.outr" %% "strider" % striderVersion,
      "org.commonmark" % "commonmark" % commonmarkVersion,
      "software.amazon.awssdk" % "s3" % awsS3Version exclude ("software.amazon.awssdk", "netty-nio-client"),
      "com.knuddels" % "jtokkit" % jtokkitVersion,
      // WebP decode/encode for ImageDownscale (#401) — stock javax.imageio
      // can't read or write webp, so a tall webp screenshot slipped through the
      // downscaler untouched. TwelveMonkeys auto-registers its readers/writers
      // via the ImageIO SPI; no code beyond the dependency.
      "com.twelvemonkeys.imageio" % "imageio-webp" % twelveMonkeysVersion,
      "com.twelvemonkeys.imageio" % "imageio-core" % twelveMonkeysVersion,
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
  .dependsOn(core % "compile->compile;test->test", script % "test->compile")
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
  .dependsOn(core, secrets, script, mcp, metals, tooling, debug, browser, streamBrowser)
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

/**
 * WebRTC preview streaming for `sigil-browser` conversations. Split from
 * `browser` because `robobrowser-stream` drags in the GStreamer JNA bindings
 * (and expects native GStreamer + Xvfb at runtime) — apps that only automate a
 * headless browser must not pay that. The CDP-screencast fallback lives here
 * too, so a consumer wires one module and gets both rungs of the ladder.
 */
lazy val streamBrowser = (project in file("browser-stream"))
  .dependsOn(browser % "compile->compile;test->test")
  .settings(docNoLinkWarnings *)
  .settings(
    name := "sigil-browser-stream",
    libraryDependencies ++= Seq(
      ("com.outr" %% "robobrowser-stream" % robobrowserVersion)
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
        // Xvfb and the GStreamer element registry are discovered through the
        // ambient environment (PATH, GST_PLUGIN_PATH, XDG_RUNTIME_DIR), so the
        // per-suite fork inherits it rather than booting bare.
        runPolicy = Tests.SubProcess(ForkOptions().withEnvVars(sys.env))
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
