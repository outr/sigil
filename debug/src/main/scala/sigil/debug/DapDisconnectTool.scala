package sigil.debug

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class DapDisconnectInput(sessionId: String,
                              terminateDebuggee: Boolean = false) extends ToolInput derives RW

/**
 * End a debug session. `terminateDebuggee = true` kills the
 * debugged program; `false` (default) detaches and leaves it
 * running. After this call, the session id is freed and the agent
 * can launch a new one with the same id.
 */
final class DapDisconnectTool(val manager: DapManager) extends TypedTool[DapDisconnectInput](
  name = ToolName("dap_disconnect"),
  description =
    """End a debug session.
      |
      |`sessionId` selects the active session.
      |`terminateDebuggee` (default false) — when true, kill the debugged program; otherwise detach.""".stripMargin,
  examples = List(
    ToolExample(
      "detach without killing the program",
      DapDisconnectInput(sessionId = "demo-session")
    ),
    ToolExample(
      "kill the debugged program on disconnect",
      DapDisconnectInput(sessionId = "demo-session", terminateDebuggee = true)
    )
  )
) with DapToolSupport {
  override protected def executeTyped(input: DapDisconnectInput, context: TurnContext): Stream[Event] =
    withSession(input.sessionId, context) { session =>
      session.disconnect(input.terminateDebuggee).flatMap(_ => manager.disconnect(input.sessionId)).map { _ =>
        s"Session '${input.sessionId}' disconnected${if (input.terminateDebuggee) " (debuggee terminated)" else ""}."
      }
    }
}
