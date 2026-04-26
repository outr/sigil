package sigil.script

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[ExecuteScriptTool]] — a single block of source code to
 * be evaluated by the configured [[ScriptExecutor]]. The model writes
 * the code; the framework deserializes into this case class, hands
 * the value off to the executor, and surfaces the result via
 * [[ScriptResult]].
 *
 * `language` is informational — apps that wire multiple executors
 * (Scala + JS, say) can dispatch on it. The default executor ignores
 * it and runs Scala 3 unconditionally.
 */
case class ScriptInput(code: String,
                       language: Option[String] = None) extends ToolInput derives RW
