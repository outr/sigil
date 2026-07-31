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

case class DapStepInInput(sessionId: String, threadId: Int) extends ToolInput derives RW

/**
 * Step into a nested method call at the current line. If there's no
 * call at the cursor, behaves like step-over.
 */
final class DapStepInTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapStepInInput
  type Output = TextToolOutput
  val io: ToolIO[DapStepInInput, TextToolOutput] = ToolIO.derived[DapStepInInput, TextToolOutput].withExamples(
    ToolExample(
      "step into a method",
      DapStepInInput(sessionId = "demo-session", threadId = 1)
    )
  )
  override val name = ToolName("dap_step_in")
  override val description =
    """Step into a nested method call at the current line.
      |
      |`sessionId` selects the active session.
      |`threadId` is the thread to step.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("debug", "dap", "step", "into", "method", "call"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: DapStepInInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    withSession(input.sessionId, context) { session =>
      session.stepIn(input.threadId).map(_ =>
        ToolResult.success(TextToolOutput(s"Stepped in on thread ${input.threadId}.")))
    }
}
