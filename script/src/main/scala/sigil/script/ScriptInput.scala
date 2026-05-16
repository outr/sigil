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
                       /** Short (≤80 char) description of THIS script's
                         * purpose, distinct from other script executions in
                         * the same turn or session. Required — there is no
                         * useful default (sigil bug #207). Surfaces in the
                         * chip header, the event log, wire forensics, and
                         * the agent's own transcript so the script's intent
                         * is readable without decoding the code itself.
                         *
                         * Examples (good): "scan: count bug references in
                         * Scala files", "edit: remove all matched bug refs",
                         * "verify: count remaining matches after edit".
                         *
                         * Examples (bad — too generic): "script", "run
                         * code", "execute Scala", "do the thing". The
                         * framework doesn't enforce uniqueness, but
                         * [[ExecuteScriptTool]]'s description asks the
                         * model to keep this short and intent-specific so
                         * consumers can distinguish adjacent calls. */
                       summary: String,
                       language: Option[String] = None) extends ToolInput derives RW
