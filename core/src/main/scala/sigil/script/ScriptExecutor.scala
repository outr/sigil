package sigil.script

import fabric.Json
import fabric.io.JsonParser
import rapid.Task

/**
 * Pluggable code-execution engine. The default
 * [[ScalaScriptExecutor]] uses Scala 3's REPL `ScriptEngine`, but
 * apps can plug in a sandboxed runtime, a different language (JS,
 * Python via Polyglot), or a remote evaluator.
 *
 * Bindings expose host-side values (typically tool instances and a
 * [[sigil.secrets.SecretStore]]-style accessor when the secrets
 * module is wired) into the script's namespace so the script can
 * call into them by name.
 */
trait ScriptExecutor {

  /**
   * Execute `code` with `bindings` available in scope. Returns the
   * stringified result of the last expression. Implementations decide
   * whether the executor is reusable across calls (the default Scala
   * impl is — its REPL state persists between invocations).
   */
  def execute(code: String, bindings: Map[String, Any]): Task[String]

  /**
   * Execute and return the raw result without `.toString` flattening —
   * useful when callers need the typed value (e.g. a compiled `Tool`
   * instance from a tool-creation script). Default delegates to
   * [[execute]].
   */
  def executeRaw(code: String, bindings: Map[String, Any]): Task[Any] =
    execute(code, bindings)

  /**
   * Compile `source` once into a reusable [[CompiledScript]]. The
   * declared `bindingTypes` name every value the caller will supply at
   * each [[CompiledScript.invoke]]; compiling executors use them to
   * generate a typed entry point so the expensive compile pass runs a
   * single time and every invocation reuses the artifact.
   *
   * Returns `Left(errors)` when compilation fails — no `CompiledScript`
   * is produced and no script body runs. Returns `Right(compiled)` on
   * success.
   *
   * The trait default is a re-executing fallback for executors with no
   * separate compile phase: it returns immediately with a
   * `CompiledScript` whose `invoke` calls [[execute]] on every
   * invocation, JSON-parsing the stringified result (falling back to a
   * `Json` string when the output is not valid JSON). Compile-error
   * detection is therefore deferred to the first `invoke` — executors
   * that can compile ahead of time override this method to surface
   * errors before any worker runs.
   */
  def compile(source: String,
              bindingTypes: List[ScriptBinding]): Task[Either[List[CompileError], CompiledScript]] =
    Task.pure(Right(new ScriptExecutor.ReExecutingCompiledScript(this, source)))

  /**
   * Identifiers/imports the executor evaluates once at engine
   * initialization, so user scripts don't need their own `import`
   * statements for the ambient surface. Implementations should choose
   * a compact, unambiguous prelude — wide auto-imports increase the
   * risk of identifier collisions when the model picks variable
   * names. Default empty (no auto-imports). Bug #54.
   */
  def preludeImports: List[String] = Nil

  /**
   * Human-readable surface advertised to the LLM in tool descriptions
   * (`CreateScriptToolTool`, `ExecuteScriptTool`). Without this, the
   * LLM falls back to its training-data prior — which for Scala is
   * heavily Scala 2 (`scala.util.parsing.json.JSON`,
   * `scala.io.Source.fromURL`, …) — and emits code that doesn't
   * compile against the host classpath. Default `None` (the tools
   * advertise no executor surface). Bug #54.
   */
  def advertisedSurface: Option[String] = None
}

object ScriptExecutor {

  /**
   * Trait-default [[CompiledScript]] — re-runs the source through
   * [[ScriptExecutor.execute]] on every [[invoke]]. Used by executors
   * that have no ahead-of-time compile phase. A runtime throw becomes
   * `Left(message)`; a successful run's stringified output is parsed as
   * JSON (or wrapped as a JSON string when it is not valid JSON).
   */
  final class ReExecutingCompiledScript(executor: ScriptExecutor, source: String) extends CompiledScript {
    override def invoke(bindings: Map[String, Any]): Task[Either[String, Json]] =
      executor.execute(source, bindings)
        .map { rendered =>
          val json: Json = scala.util.Try(JsonParser(rendered)).getOrElse(fabric.Str(rendered))
          Right(json): Either[String, Json]
        }
        .handleError { t =>
          Task.pure(Left(Option(t.getMessage).getOrElse(t.getClass.getSimpleName)))
        }
  }
}
