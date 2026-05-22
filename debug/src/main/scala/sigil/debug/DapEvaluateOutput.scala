package sigil.debug

import fabric.rw.*
import sigil.tool.ToolOutput

/**
 * Typed result of `dap_evaluate`. `result` is the rendered value;
 * `type` is the adapter-reported type (when available);
 * `variablesReference` is non-zero when the result is structured and
 * can be expanded with a `dap_variables` call.
 */
case class DapEvaluateOutput(result: String,
                             `type`: Option[String],
                             variablesReference: Int) extends ToolOutput derives RW
