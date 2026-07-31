package sigil.debug

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}

case class DapPauseInput(sessionId: String, threadId: Int) extends ToolInput derives RW

/**
 * Pause a running thread. Useful when the program has entered a
 * loop or stuck state and the agent wants to inspect why.
 */
final class DapPauseTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapPauseInput
  type Output = TextToolOutput
  val inputRW = summon[RW[DapPauseInput]]
  val outputRW = summon[RW[TextToolOutput]]
  override val name = ToolName("dap_pause")
  override val description =
    """Pause a running thread.
      |
      |`sessionId` selects the active session.
      |`threadId` is the thread to pause.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("debug", "dap", "pause", "suspend", "thread", "interrupt"))
  )
  override val examples = List(
    ToolExample(
      "pause a thread",
      DapPauseInput(sessionId = "demo-session", threadId = 1)
    )
  )

  override def executeResult(input: DapPauseInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    withSession(input.sessionId, context) { session =>
      session.pause(input.threadId).map(_ =>
        ToolResult.success(TextToolOutput(s"Pause requested on thread ${input.threadId}.")))
    }
}
