package sigil.debug

import fabric.rw.*
import sigil.tool.ToolOutput

/**
 * One variable scope in a stack frame — e.g. "Locals", "Arguments".
 * `variablesReference` is what the agent passes to `dap_variables`.
 */
case class DapScopeInfo(name: String,
                        variablesReference: Int,
                        expensive: Boolean)
  derives RW

/**
 * Typed result of `dap_scopes` — the frame's variable scopes.
 */
case class DapScopesOutput(scopes: List[DapScopeInfo]) extends ToolOutput derives RW
