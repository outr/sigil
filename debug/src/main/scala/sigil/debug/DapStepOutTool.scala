package sigil.debug

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}

case class DapStepOutInput(sessionId: String, threadId: Int) extends ToolInput derives RW

/**
 * Run to the end of the current frame and stop in the caller. The
 * agent uses this to back out of a method when the rest of its
 * execution isn't relevant.
 */
final class DapStepOutTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapStepOutInput
  type Output = TextToolOutput
  val inputRW = summon[RW[DapStepOutInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("dap_step_out")
  val description =
    """Run to the end of the current frame and stop in the caller.
      |
      |`sessionId` selects the active session.
      |`threadId` is the thread to step.""".stripMargin
  override val examples = List(
    ToolExample(
      "step out of the current method",
      DapStepOutInput(sessionId = "demo-session", threadId = 1)
    )
  )

  override def executeResult(input: DapStepOutInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    withSession(input.sessionId, context) { session =>
      session.stepOut(input.threadId).map(_ =>
        ToolResult.success(TextToolOutput(s"Stepped out on thread ${input.threadId}.")))
    }
}
