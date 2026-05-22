package sigil.debug

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}

case class DapDisconnectInput(sessionId: String,
                              terminateDebuggee: Boolean = false) extends ToolInput derives RW

/**
 * End a debug session. `terminateDebuggee = true` kills the
 * debugged program; `false` (default) detaches and leaves it
 * running. After this call, the session id is freed and the agent
 * can launch a new one with the same id.
 */
final class DapDisconnectTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapDisconnectInput
  type Output = TextToolOutput
  val inputRW = summon[RW[DapDisconnectInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("dap_disconnect")
  val description =
    """End a debug session.
      |
      |`sessionId` selects the active session.
      |`terminateDebuggee` (default false) — when true, kill the debugged program; otherwise detach.""".stripMargin
  override val examples = List(
    ToolExample(
      "detach without killing the program",
      DapDisconnectInput(sessionId = "demo-session")
    ),
    ToolExample(
      "kill the debugged program on disconnect",
      DapDisconnectInput(sessionId = "demo-session", terminateDebuggee = true)
    )
  )
  override def paginate: Boolean = false

  override def executeResult(input: DapDisconnectInput, context: TurnContext): Task[ToolResult[TextToolOutput]] =
    withSession(input.sessionId, context) { session =>
      session.disconnect(input.terminateDebuggee).flatMap(_ => manager.disconnect(input.sessionId)).map { _ =>
        val suffix = if (input.terminateDebuggee) " (debuggee terminated)" else ""
        ToolResult.success(TextToolOutput(s"Session '${input.sessionId}' disconnected$suffix."))
      }
    }
}
