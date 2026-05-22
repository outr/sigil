package sigil.script

import fabric.rw.*
import sigil.tool.ToolOutput

/**
 * Typed output of a script execution — produced by both
 * [[ExecuteScriptTool]] (ad-hoc evaluation) and a [[ScriptTool]]
 * record's stored-script invocation.
 *
 * `output` is the executor's stringified return value; `error` is set
 * instead when the script threw (carrying an abbreviated stack trace
 * so wrapped exceptions surface their root cause). `durationMs` is the
 * wall-clock execution time.
 */
case class ScriptToolOutput(output: Option[String] = None,
                            error: Option[String] = None,
                            durationMs: Long = 0L) extends ToolOutput derives RW
