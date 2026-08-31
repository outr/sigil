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
  // System.out / System.err to this stream, so all REPL output
  // (errors, warnings, defs) lands here.
  private[script] val captured: ByteArrayOutputStream = new ByteArrayOutputStream()
  private val capturedPS: PrintStream = new PrintStream(captured, /*autoFlush*/ true)

  private[script] lazy val engine: ScriptEngine = {
    // Resolve the compiler's classpath in priority order:
    //   1. `classpathOverride` — caller knows best.
    //   2. URL introspection of the context classloader —
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
    // `dotty.tools.repl.ScriptEngine` builds its `ReplDriver` (and the
    // diagnostic reporter) at construction, binding to the ambient output
    // streams THEN. Scala 3.8.4's reporter writes to `System.out`/`System.err`
    // (not the `Console.out` we set via `Console.withOut`), and it caches the
    // reference at construction, so an eval-time redirect is too late. Bind
    // ALL of Console.out + System.out + System.err to `capturedPS` for the
    // duration of construction so every later eval's diagnostics land in
    // `captured`, whichever stream the active REPL version targets.
    def buildEngine(): ScriptEngine = {
      val priorOut = System.out
      val priorErr = System.err
      System.setOut(capturedPS)
      System.setErr(capturedPS)
      try Console.withOut(capturedPS)(ScriptEngine())
      finally {
        System.setOut(priorOut)
        System.setErr(priorErr)
      }
    }
    val e = resolvedClasspath match {
      case Some(cp) =>
        val original = System.getProperty("java.class.path")
        System.setProperty("java.class.path", cp)
        try buildEngine()
        finally
          if (original == null) System.clearProperty("java.class.path")
          else System.setProperty("java.class.path", original)
      case None =>
        buildEngine()
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

  /**
   * Default Scala 3 prelude — Fabric for JSON, Spice for HTTP, Rapid
   * for async, plus stdlib bridges that LLM training data routinely
   * picks the Scala 2 form for (`scala.collection.JavaConversions`).
   * Apps that need a different surface override.
   */
  override def preludeImports: List[String] = List(
    "fabric.*",
    "fabric.io.{JsonParser, JsonFormatter}",
    "fabric.rw.*",
    "spice.http.client.HttpClient",
    "spice.http.{HttpRequest, HttpResponse}",
    "spice.net.*",
    "rapid.Task",
    "scala.jdk.CollectionConverters.*",
    "sigil.tool.{ToolInput, ToolName}",
    "sigil.tool.model.*"
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
      |  - `sigil.tool.model.*` for tool input/output case classes (BashInput,
      |    ReadFileInput, HttpRequestInput, …) — the shapes other tools take and emit.
      |Pre-bound (no construction needed):
      |  - `tools.callTool[Out](name, input)` invokes any host tool by name and decodes the
      |    typed result. Use `find_capability` FIRST (a sibling tool) to discover which
      |    tools exist and what their inputs / outputs look like — then call them from
      |    inside the script by name via `tools.callTool`. `tools.has("name")` returns
      |    `true` when a tool is registered. `tools.callToolJson(name, input)` returns
      |    the raw JSON payload.
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

  /**
   * Compile `source` once into a [[ScalaCompiledScript]]. The user
   * code is wrapped in a uniquely-named top-level `def` whose
   * parameters are the declared `bindingTypes`; the `def` is evaluated
   * a single time so the heavy compile happens once, and every
   * [[CompiledScript.invoke]] only re-binds argument values and calls
   * the persisted `def`.
   *
   * On a compile failure the captured REPL diagnostics are parsed into
   * typed [[CompileError]]s via [[ScalaDiagnosticParser]] and returned
   * as `Left`; no `CompiledScript` is produced and no body runs.
   */
  override def compile(source: String,
                       bindingTypes: List[ScriptBinding]): Task[Either[List[CompileError], CompiledScript]] = Task {
    engine.synchronized {
      val cleaned = stripCodeFences(source)
      val defName = s"__sigilCompiled_${java.lang.Long.toHexString(System.nanoTime())}"
      val params = bindingTypes.map(b => s"${b.name}: ${b.typeName}").mkString(", ")
      val wrapped = s"def $defName($params): Any = {\n$cleaned\n}"
      captured.reset()
      engine.eval(wrapped)
      val diagnostics = captured.toString.trim
      if (containsErrorDiagnostic(diagnostics)) {
        val errors = ScalaDiagnosticParser.parse(diagnostics)
        val nonEmpty = if (errors.nonEmpty) errors else List(CompileError(0, 0, diagnostics))
        Left(nonEmpty): Either[List[CompileError], CompiledScript]
      } else {
        Right(new ScalaCompiledScript(engine, captured, defName, bindingTypes))
      }
    }
  }

  override def executeRaw(code: String, bindings: Map[String, Any]): Task[Any] = Task {
    engine.synchronized {
      bindAll(bindings)
      val cleaned = stripCodeFences(code)
      // Reset captured output before the eval so any error markers we
      captured.reset()
      // Wrap the user code in a generated `def` whose body is the
      // user's full source, then call it. Scala's function-body rules
      // make the body's value EXACTLY the value of its trailing
      // expression, so a script like `println(summary); summary`
      // returns `summary` instead of the println's `Unit` (the prior
      // REPL behaviour anchored on the last *statement* with a
      // recordable value and silently surfaced `()` for side-effecting
      // trailing lines).
      //
      // Plain `{ … }` wrapping wasn't enough — the dotty REPL's
      // `ScriptEngine.eval` parses the block contents as a
      // sequence of top-level statements and returns the value of
      // the last one it recorded, ignoring block-as-expression
      // semantics. A def body, by contrast, is compiled as a single
      // expression whose result type is whatever the trailing
      // expression evaluates to.
      //
      // Caveat: `val`/`var` bindings inside the user's code become
      // local to the synthesized def rather than persisted in the
      // REPL's namespace. The [[ScriptAuthoringMode]] skill already
      // says "No global state" and treats each script as
      // self-contained, so this is intentional rather than regressive.
      val defName = s"__sigilScript_${java.lang.Long.toHexString(System.nanoTime())}"
      val wrapped = s"def $defName() = {\n$cleaned\n}\n$defName()"
      // Bug #55 — the Scala 3 REPL `ScriptEngine` reports compile failure in
      // two different ways across versions: older releases returned `null`
      // (caught by the post-eval diagnostic check below); 3.8.4 throws while
      // reflectively loading the never-generated result class (e.g.
      // `ClassNotFoundException: rs$line$N`) after `driver.run` reports the
      // diagnostic. The diagnostic lands in `captured` because the driver's
      // reporter was bound to it at engine construction (see `engine`). Catch
      // the throw and, when the captured output is a real compile diagnostic,
      // surface it as a `ScriptCompileException` carrying the human-readable
      // error text so both REPL behaviours collapse to the same typed failure.
      // A throw with no compile diagnostic is a genuine runtime error —
      // rethrow it as-is.
      val result =
        try engine.eval(wrapped)
        catch {
          case t: Throwable =>
            val diag = captured.toString.trim
            if (containsErrorDiagnostic(diag)) throw new ScriptCompileException(diag)
            else throw t
        }
      val diagnostics = captured.toString.trim
      if (containsErrorDiagnostic(diagnostics)) {
        throw new ScriptCompileException(diagnostics)
      }
      result
    }
  }

  /**
   * True if the captured REPL output contains a Scala 3 error
   * diagnostic. Scala 3's `ConsoleReporter` formats errors as
   * `-- [E<num>] <Category>: -----...` (with optional category like
   * "Type Error", "Syntax Error"). Falls back to a looser check for
   * `error:` lines so we catch any reporter format we don't
   * specifically recognize. Warnings are intentionally NOT
   * triggers — successful-but-noisy compiles still return their
   * value.
   */
  private def containsErrorDiagnostic(out: String): Boolean =
    if (out.isEmpty) false
    else {
      val errorMarker = "-- [E"
      val errorLineMarker = " error:"
      val lines = out.linesIterator
      lines.exists(l => l.contains(errorMarker) || l.contains(errorLineMarker))
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

  /**
   * Best-effort: walk the context classloader chain, gather URLs
   * from any `URLClassLoader` ancestors, and join their filesystem
   * paths into a `File.pathSeparator`-separated classpath string
   * suitable for [[ScalaScriptExecutor]]'s `classpathOverride`.
   * Falls back to `java.class.path` when the loader chain has no
   * `URLClassLoader` ancestors (sbt 1, fat-jar launches, jlink
   * images) so callers always get a classpath under any test JVM
   * shape.
   *
   * Returns `None` only when both the URL walk and `java.class.path`
   * are empty (extremely unusual).
   */
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
    if (urls.nonEmpty) Some(urls.mkString(File.pathSeparator))
    else Option(System.getProperty("java.class.path")).filter(_.nonEmpty)
  }
}
