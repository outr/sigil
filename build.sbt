ThisBuild / organization := "com.outr"
ThisBuild / version := "1.2.0-SNAPSHOT16"

ThisBuild / scalaVersion := "3.8.3"

val rapidVersion: String = "2.9.9"

val spiceVersion: String = "1.10.2"

val profigVersion: String = "3.7.1"

val scribeVersion: String = "3.19.0"

val lightdbVersion: String = "4.44.0"

val striderVersion: String = "1.1.5"

val scalapassVersion: String = "1.4.2"

val awsS3Version: String = "2.46.10"

val robobrowserVersion: String = "2.3.5"

val commonmarkVersion: String = "0.28.0"

val lsp4jVersion: String = "1.0.0"

val bsp4jVersion: String = "2.2.0-M4.TEST"

val lsp4jDebugVersion: String = "1.0.0"

val jtokkitVersion: String = "1.1.0"

val twelveMonkeysVersion: String = "3.12.0"

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
  "-unchecked"
)
ThisBuild / javaOptions ++= Seq("-Xmx16G", "-Xss4m", "-XX:MaxMetaspaceSize=2g")

Global / concurrentRestrictions := Seq(
  Tags.limit(Tags.ForkedTestGroup, 4),
  Tags.limit(Tags.Test, 4)
)

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
