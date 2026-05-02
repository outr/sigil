package sigil.script

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
