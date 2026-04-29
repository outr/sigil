package sigil.debug

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class DapScopesInput(sessionId: String, frameId: Int) extends ToolInput derives RW

/**
 * Fetch the variable scopes available in a stack frame — typically
 * "Locals", "Arguments", "Globals". Each scope carries a
 * `variablesReference` the agent passes to `dap_variables` to
 * actually fetch the named bindings.
 */
final class DapScopesTool(val manager: DapManager) extends TypedTool[DapScopesInput](
  name = ToolName("dap_scopes"),
  description =
    """Fetch the variable scopes (Locals / Arguments / Globals / etc.) for a frame.
      |
      |`sessionId` selects the active session.
      |`frameId` is from the most-recent `dap_stack_trace` for the stopped thread.
      |Returns each scope's name and `variablesReference` for the next call.""".stripMargin,
  examples = List(
    ToolExample(
      "fetch scopes for the top frame",
      DapScopesInput(sessionId = "demo-session", frameId = 1000)
    )
  )
) with DapToolSupport {
  override protected def executeTyped(input: DapScopesInput, context: TurnContext): Stream[Event] =
    withSession(input.sessionId, context) { session =>
      session.scopes(input.frameId).map { scopes =>
        if (scopes.isEmpty) "No scopes."
        else scopes.map { s =>
          val expensive = if (s.isExpensive) " (expensive)" else ""
          s"  [${s.getVariablesReference}] ${s.getName}$expensive"
        }.mkString("\n")
      }
    }
}
