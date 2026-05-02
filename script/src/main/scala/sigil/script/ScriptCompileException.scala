package sigil.script

/**
 * Raised when a [[ScriptExecutor]] receives source that fails to
 * compile. Distinct from runtime throws so apps that want different
 * retry / fallback policies for compile vs. runtime failures can
 * pattern-match on the type.
 *
 * The message carries the executor's diagnostic output verbatim —
 * for `ScalaScriptExecutor` that's the Scala 3 REPL's
 * `ConsoleReporter`-formatted error output (`-- [E<num>] ...`),
 * including line/column markers and the offending source — so the
 * agent's next turn has the same diagnostic the developer would see
 * at the console. Bug #55.
 */
class ScriptCompileException(message: String) extends RuntimeException(message)
