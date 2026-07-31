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

case class DapStepOutInput(sessionId: String, threadId: Int) extends ToolInput derives RW

/**
 * Run to the end of the current frame and stop in the caller. The
 * agent uses this to back out of a method when the rest of its
 * execution isn't relevant.
 */
final class DapStepOutTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapStepOutInput
  type Output = TextToolOutput
  val io: ToolIO[DapStepOutInput, TextToolOutput] = ToolIO.derived[DapStepOutInput, TextToolOutput].withExamples(
    ToolExample(
      "step out of the current method",
      DapStepOutInput(sessionId = "demo-session", threadId = 1)
    )
  )
  override val name = ToolName("dap_step_out")
  override val description =
    """Run to the end of the current frame and stop in the caller.
      |
      |`sessionId` selects the active session.
      |`threadId` is the thread to step.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("debug", "dap", "step", "out", "return", "caller"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: DapStepOutInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    withSession(input.sessionId, context) { session =>
      session.stepOut(input.threadId).map(_ =>
        ToolResult.success(TextToolOutput(s"Stepped out on thread ${input.threadId}.")))
    }
}
