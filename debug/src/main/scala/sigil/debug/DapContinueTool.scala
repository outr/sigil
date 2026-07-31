package sigil.debug

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}

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
  override val name = ToolName("dap_continue")
  override val description =
    """Resume execution from a stopped state.
      |
      |`sessionId` selects the active session.
      |`threadId` is the thread to resume (from `dap_threads` or the latest stopped event).""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("debug", "dap", "continue", "resume", "run", "execution"))
  )
  override val examples = List(
    ToolExample(
      "resume from a breakpoint",
      DapContinueInput(sessionId = "demo-session", threadId = 1)
    )
  )

  override def executeResult(input: DapContinueInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    withSession(input.sessionId, context) { session =>
      session.continueExecution(input.threadId).map { allThreads =>
        val text = if (allThreads) "All threads resumed." else s"Thread ${input.threadId} resumed."
        ToolResult.success(TextToolOutput(text))
      }
    }
}
