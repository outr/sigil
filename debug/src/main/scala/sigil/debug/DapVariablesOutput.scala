package sigil.debug

import fabric.rw.*
import sigil.tool.ToolOutput

/**
 * One variable binding. `variablesReference` is non-zero when the
 * value is structured and can be expanded with a further
 * `dap_variables` call.
 */
case class DapVariableInfo(name: String,
                           value: String,
                           `type`: Option[String],
                           variablesReference: Int) derives RW

/**
 * Typed result of `dap_variables`. `variables` is the (possibly
 * capped) binding list; `truncated` holds the count of bindings
 * elided when the scope exceeded `maxResults`.
 */
case class DapVariablesOutput(variables: List[DapVariableInfo],
                              truncated: Int) extends ToolOutput derives RW
