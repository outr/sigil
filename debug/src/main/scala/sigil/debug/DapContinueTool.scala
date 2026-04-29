package sigil.debug

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class DapContinueInput(sessionId: String, threadId: Int) extends ToolInput derives RW

/**
 * Resume execution from a stopped state. The agent calls this after
 * inspecting state at a breakpoint to let the program run until the
 * next stop event. Use `dap_session_status` afterward to wait for
 * the next pause.
 */
final class DapContinueTool(val manager: DapManager) extends TypedTool[DapContinueInput](
  name = ToolName("dap_continue"),
  description =
    """Resume execution from a stopped state.
      |
      |`sessionId` selects the active session.
      |`threadId` is the thread to resume (from `dap_threads` or the latest stopped event).""".stripMargin,
  examples = List(
    ToolExample(
      "resume from a breakpoint",
      DapContinueInput(sessionId = "demo-session", threadId = 1)
    )
  )
) with DapToolSupport {
  override protected def executeTyped(input: DapContinueInput, context: TurnContext): Stream[Event] =
    withSession(input.sessionId, context) { session =>
      session.continueExecution(input.threadId).map { allThreads =>
        if (allThreads) s"All threads resumed."
        else s"Thread ${input.threadId} resumed."
      }
    }
}
