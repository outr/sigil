package sigil.script

import dotty.tools.repl.ScriptEngine
import rapid.Task

import java.io.{ByteArrayOutputStream, File, PrintStream}
import java.net.URLClassLoader

/**
 * [[ScriptExecutor]] backed by Scala 3's REPL `ScriptEngine`.
 *
 * Bindings are injected via [[ScriptValueHolder]] and a synthetic
 * `val name = ScriptValueHolder.store[Type]` line evaluated in the
 * REPL — this is the trick that lets arbitrary host values flow into
 * Scala scope without compile-time knowledge.
 *
 * **Thread-safety.** All operations synchronize on the engine
 * instance. The REPL's state (imports, prior bindings) persists
 * across calls, so a single `ScalaScriptExecutor` is effectively a
 * stateful session — apps that need session isolation construct one
 * per logical session.
 *
 * **Security.** This executes arbitrary Scala — full JVM access.
 * Apps running untrusted scripts (e.g., user-authored code through a
 * web UI) MUST front this with a sandbox or run remotely via
 * [[sigil.tool.proxy.ProxyTool]] against a dedicated executor
 * process. The framework itself does no sandboxing.
 *
 * Code fences (` ```scala ... ``` `) common in LLM output are
 * stripped before evaluation — saves a layer of "the model wrapped
 * its answer in markdown" parsing.
 */
class ScalaScriptExecutor(classpathOverride: Option[String] = None) extends ScriptExecutor {
  // Bug #55 — the Scala 3 REPL `ScriptEngine` writes compile
  // diagnostics to its `out` PrintStream (defaults to
  // `Console.out`) and returns `null` on failure. Capturing the
  // output stream lets us drain diagnostics after each eval and
  // raise a `ScriptCompileException` instead of silently producing
  // empty results.
  //
  // `Console.withOut(capturedPS) { ScriptEngine() }` locks the
  // driver's `out` to our capture for its lifetime — subsequent
  // engine.eval() calls run their `withRedirectedOutput` redirect
  // System.out / System.err to this stream, so all REPL output
  // (errors, warnings, defs) lands here.
  private val captured: ByteArrayOutputStream = new ByteArrayOutputStream()
  private val capturedPS: PrintStream = new PrintStream(captured, /*autoFlush*/ true)

  private lazy val engine = {
    // Resolve the compiler's classpath in priority order:
    //   1. `classpathOverride` — caller knows best.
    //   2. URL introspection of the context classloader (bug #58) —
    //      handles sbt 2 test workers, IDE runners, Bazel binaries,
    //      and any other launcher whose effective classpath lives
    //      in a `URLClassLoader` rather than `java.class.path`.
    //   3. `None` → fall through to `ScriptEngine()`'s default
    //      `-usejavacp` path, which reads `java.class.path`. Works
    //      for the canonical `java -cp <full> ClassName` shape.
    //
    // `dotty.tools.repl.ScriptEngine` doesn't expose a settings-arg
    // constructor and its `driver` field is `private val`. To swap
    // the classpath without forking dotty, we temporarily override
    // `java.class.path` while constructing the engine — the driver
    // reads the property once at construction and caches the
    // classpath, so we restore the original immediately after.
    val resolvedClasspath: Option[String] =
      classpathOverride.orElse(ScalaScriptExecutor.detectClasspathFromContext())
    val e = resolvedClasspath match {
      case Some(cp) =>
        val original = System.getProperty("java.class.path")
        System.setProperty("java.class.path", cp)
        try Console.withOut(capturedPS)(ScriptEngine())
        finally
          if (original == null) System.clearProperty("java.class.path")
          else System.setProperty("java.class.path", original)
      case None =>
        Console.withOut(capturedPS)(ScriptEngine())
    }
    // Bug #54 — evaluate the prelude exactly once at engine init, so
    // every subsequent script runs with the ambient surface
    // pre-imported. Drain captured output afterwards so prelude
    // chatter (typedef listings, harmless warnings) doesn't leak
    // into the first user script's diagnostic check.
    preludeImports.foreach(imp => e.eval(s"import $imp"))
    captured.reset()
    e
  }

  /** Default Scala 3 prelude — Fabric for JSON, Spice for HTTP, Rapid
    * for async, plus stdlib bridges that LLM training data routinely
    * picks the Scala 2 form for (`scala.collection.JavaConversions`).
    * Apps that need a different surface override. */
  override def preludeImports: List[String] = List(
    "fabric.*",
    "fabric.io.{JsonParser, JsonFormatter}",
    "fabric.rw.*",
    "spice.http.client.HttpClient",
    "spice.http.{HttpRequest, HttpResponse}",
    // Bug #70 — wildcard `spice.net.*` brings the `url"…"` /
    // `path"…"` / `port"…"` / `ip"…"` / `email"…"` literal
    // interpolators into scope, plus the `URL` case class itself.
    // The cookbook uses `url"…"` to lift literal Strings into typed
    // URLs (the `HttpClient.url(_: URL)` signature requires a typed
    // URL, not a String — this is the cookbook's #1 footgun pre-fix).
    "spice.net.*",
    "rapid.Task",
    "scala.jdk.CollectionConverters.*"
  )

  override def advertisedSurface: Option[String] = Some(
    """Script bodies are Scala 3 evaluated by the Scala REPL.
      |Pre-imported (no import statement needed):
      |  - `fabric.*` and `fabric.io.{JsonParser, JsonFormatter}` for JSON
      |  - `spice.http.client.HttpClient` + `spice.http.{HttpRequest, HttpResponse}` for HTTP
      |  - `spice.net.*` — `URL` case class plus the `url"…"` / `path"…"` / `port"…"` /
      |    `ip"…"` / `email"…"` literal interpolators that lift compile-time-validated
      |    Strings into their typed wrappers (use `url"https://example.com/path"` to pass
      |    a literal URL to `HttpClient.url(_: URL)`)
      |  - `rapid.Task` for async (use `.sync()` to block at script boundary)
      |  - `scala.jdk.CollectionConverters.*` for Java↔Scala collection bridging
      |Avoid: `scala.util.parsing.json` (removed in Scala 3 — use `JsonParser(...)` instead),
      |`scala.io.Source.fromURL` for HTTP (use `HttpClient` instead),
      |`scala.collection.JavaConversions` (deprecated — `scala.jdk.CollectionConverters` is pre-imported).
      |Other languages (Python, JS) are not supported by this executor — write Scala 3.
      |
      |API quirks worth knowing:
      |  - `HttpClient.url(_)` takes a typed `spice.net.URL`, NOT a `String`. Use
      |    `url"https://…"` for literals or `URL.parse("…")` for dynamic strings.
      |  - `Content.asString` returns `Task[String]`, NOT `String`. Either
      |    `response.content.get.asString.sync()` to materialize, or chain via
      |    `.flatMap` / `.map` for async composition.
      |  - `HttpClient.post` is a no-arg method (sets the HTTP method). Use
      |    `.json(jsonBody)` to attach a JSON body, or `.content(StringContent(...))`
      |    for arbitrary content.""".stripMargin
  )

  override def execute(code: String, bindings: Map[String, Any]): Task[String] =
    executeRaw(code, bindings).map(r => if (r == null) "" else r.toString)

  override def executeRaw(code: String, bindings: Map[String, Any]): Task[Any] = Task {
    synchronized {
      bindAll(bindings)
      val cleaned = stripCodeFences(code)
      // Reset captured output before the eval so any error markers we
      // see afterwards are from this call only. Bug #55.
      captured.reset()
      val result = engine.eval(cleaned)
      val diagnostics = captured.toString.trim
      if (containsErrorDiagnostic(diagnostics)) {
        throw new ScriptCompileException(diagnostics)
      }
      result
    }
  }

  /** True if the captured REPL output contains a Scala 3 error
    * diagnostic. Scala 3's `ConsoleReporter` formats errors as
    * `-- [E<num>] <Category>: -----...` (with optional category like
    * "Type Error", "Syntax Error"). Falls back to a looser check for
    * `error:` lines so we catch any reporter format we don't
    * specifically recognize. Warnings are intentionally NOT
    * triggers — successful-but-noisy compiles still return their
    * value. Bug #55. */
  private def containsErrorDiagnostic(out: String): Boolean = {
    if (out.isEmpty) false
    else {
      val errorMarker = "-- [E"
      val errorLineMarker = " error:"
      val lines = out.linesIterator
      lines.exists(l => l.contains(errorMarker) || l.contains(errorLineMarker))
    }
  }

  private def bindAll(bindings: Map[String, Any]): Unit =
    bindings.foreach { case (key, value) =>
      ScriptValueHolder.store = value
      val typeName = Option(value.getClass.getCanonicalName).getOrElse(value.getClass.getName)
      engine.eval(s"val $key = sigil.script.ScriptValueHolder.store.asInstanceOf[$typeName]")
      // Force the val to evaluate so the ThreadLocal is read before being overwritten.
      engine.eval(key)
    }

  private def stripCodeFences(code: String): String = code.trim match {
    case s if s.startsWith("```") =>
      val lines = s.linesIterator.toList
      val withoutOpening = lines.drop(1)
      val withoutClosing = if (withoutOpening.lastOption.exists(_.trim == "```")) withoutOpening.dropRight(1) else withoutOpening
      withoutClosing.mkString("\n")
    case s => s
  }
}

object ScalaScriptExecutor {

  /** Best-effort: walk the context classloader chain, gather URLs
    * from any `URLClassLoader` ancestors, and join their filesystem
    * paths into a `File.pathSeparator`-separated classpath string
    * suitable for [[ScalaScriptExecutor]]'s `classpathOverride`.
    *
    * Returns `None` when the loader chain has no `URLClassLoader`
    * ancestors (Java 17+ `AppClassLoader` for fat-jar launches,
    * jlink images, Bazel binaries, etc.) — apps in those environments
    * compute the classpath through their own mechanism (sbt's
    * `Test / fullClasspath`, an explicit list of jars, etc.).
    *
    * Bug #57 — sbt 2 test workers populate `java.class.path` with
    * only sbt's own plumbing; the real test classpath lives in a
    * `URLClassLoader` the worker constructs manually. Calling this
    * from inside such a worker recovers the full classpath. */
  def detectClasspathFromContext(): Option[String] = {
    val loader = Thread.currentThread().getContextClassLoader
    val urls = collection.mutable.LinkedHashSet.empty[String]
    var current: ClassLoader = loader
    while (current != null) {
      current match {
        case ucl: URLClassLoader =>
          ucl.getURLs.foreach { url =>
            try urls += new File(url.toURI).getAbsolutePath
            catch { case _: Throwable => () }
          }
        case _ => ()
      }
      current = current.getParent
    }
    if (urls.isEmpty) None else Some(urls.mkString(File.pathSeparator))
  }
}
