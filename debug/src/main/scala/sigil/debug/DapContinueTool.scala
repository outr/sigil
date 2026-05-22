package sigil.debug

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}

case class DapContinueInput(sessionId: String, threadId: Int) extends ToolInput derives RW

/**
 * Resume execution from a stopped state. The agent calls this after
 * inspecting state at a breakpoint to let the program run until the
 * next stop event. Use `dap_session_status` afterward to wait for
 * the next pause.
 */
final class DapContinueTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapContinueInput
  type Output = TextToolOutput
  val inputRW = summon[RW[DapContinueInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("dap_continue")
  val description =
    """Resume execution from a stopped state.
      |
      |`sessionId` selects the active session.
      |`threadId` is the thread to resume (from `dap_threads` or the latest stopped event).""".stripMargin
  override val examples = List(
    ToolExample(
      "resume from a breakpoint",
      DapContinueInput(sessionId = "demo-session", threadId = 1)
    )
  )
  override def paginate: Boolean = false

  override def executeResult(input: DapContinueInput, context: TurnContext): Task[ToolResult[TextToolOutput]] =
    withSession(input.sessionId, context) { session =>
      session.continueExecution(input.threadId).map { allThreads =>
        val text = if (allThreads) "All threads resumed." else s"Thread ${input.threadId} resumed."
        ToolResult.success(TextToolOutput(text))
      }
    }
}
