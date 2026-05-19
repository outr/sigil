package sigil.script

/**
 * Declares one named value a [[ScriptExecutor.compile]] caller will
 * supply at every [[CompiledScript.invoke]]. Compiling executors use
 * the declared `typeName` to generate a typed parameter for the
 * compiled artifact so the heavy compile happens once and every
 * invocation just re-supplies argument values.
 *
 *   - `name`     — the identifier the script body references.
 *   - `typeName` — the fully-qualified Scala type of the value (e.g.
 *     `fabric.Json`, `sigil.TurnContext`). Used verbatim in the
 *     generated parameter list.
 */
case class ScriptBinding(name: String, typeName: String)
