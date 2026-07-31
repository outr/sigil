package sigil.debug

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{
  DiscoverySpec,
  Effect,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolExample,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

case class DapStepOverInput(sessionId: String, threadId: Int) extends ToolInput derives RW

/**
 * Execute the next statement in the current frame, stepping over
 * any nested method calls. The classic "next" debugger command.
 */
final class DapStepOverTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapStepOverInput
  type Output = TextToolOutput
  val io: ToolIO[DapStepOverInput, TextToolOutput] = ToolIO.derived[DapStepOverInput, TextToolOutput].withExamples(
    ToolExample(
      "step over the next line",
      DapStepOverInput(sessionId = "demo-session", threadId = 1)
    )
  )
  override val name = ToolName("dap_step_over")
  override val description =
    """Step over the next statement in the current frame (don't enter nested calls).
      |
      |`sessionId` selects the active session.
      |`threadId` is the thread to step.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("debug", "dap", "step", "over", "next", "statement"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: DapStepOverInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    withSession(input.sessionId, context) { session =>
      session.next(input.threadId).map(_ =>
        ToolResult.success(TextToolOutput(s"Stepped over on thread ${input.threadId}.")))
    }
}
