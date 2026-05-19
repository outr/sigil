package sigil.script

import fabric.Json
import rapid.Task

/**
 * A successfully compiled script artifact returned by
 * [[ScriptExecutor.compile]]. The heavy compile happens once; every
 * [[invoke]] reuses the artifact with a fresh set of argument values.
 *
 * `invoke` is parallel-safe — multiple fibers may call it
 * concurrently against the same `CompiledScript`. Implementations
 * that wrap a stateful compiler engine must serialize internally.
 *
 * The result discriminates a runtime failure (`Left` — the script
 * threw) from a successful return value (`Right` — the script's
 * trailing-expression value, JSON-encoded). A compile failure is not
 * representable here — it surfaces from `compile` itself before any
 * `CompiledScript` exists.
 */
trait CompiledScript {

  /**
   * Run the compiled script with `bindings` supplying the declared
   * binding values by name. Returns `Right(json)` for the script's
   * return value, `Left(message)` for a runtime throw.
   */
  def invoke(bindings: Map[String, Any]): Task[Either[String, Json]]
}
