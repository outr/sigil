package sigil.debug

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolExample, ToolInput, ToolName, ToolResult}

case class DapScopesInput(sessionId: String, frameId: Int) extends ToolInput derives RW

/**
 * Fetch the variable scopes available in a stack frame — typically
 * "Locals", "Arguments", "Globals". Each scope carries a
 * `variablesReference` the agent passes to `dap_variables` to
 * actually fetch the named bindings.
 */
final class DapScopesTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapScopesInput
  type Output = DapScopesOutput
  val inputRW = summon[RW[DapScopesInput]]
  val outputRW = summon[RW[DapScopesOutput]]
  val name = ToolName("dap_scopes")
  val description =
    """Fetch the variable scopes (Locals / Arguments / Globals / etc.) for a frame.
      |
      |`sessionId` selects the active session.
      |`frameId` is from the most-recent `dap_stack_trace` for the stopped thread.
      |Returns each scope's name and `variablesReference` for the next call.""".stripMargin
  override val examples = List(
    ToolExample(
      "fetch scopes for the top frame",
      DapScopesInput(sessionId = "demo-session", frameId = 1000)
    )
  )

  override def executeResult(input: DapScopesInput, context: ToolContext): Task[ToolResult[DapScopesOutput]] =
    withSession(input.sessionId, context) { session =>
      session.scopes(input.frameId).map { scopes =>
        ToolResult.success(DapScopesOutput(
          scopes.map { s =>
            DapScopeInfo(
              name = s.getName,
              variablesReference = s.getVariablesReference,
              expensive = s.isExpensive
            )
          }
        ))
      }
    }
}
