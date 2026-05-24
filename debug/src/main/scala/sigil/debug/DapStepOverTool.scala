package sigil.debug

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}

case class DapStepOverInput(sessionId: String, threadId: Int) extends ToolInput derives RW

/**
 * Execute the next statement in the current frame, stepping over
 * any nested method calls. The classic "next" debugger command.
 */
final class DapStepOverTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapStepOverInput
  type Output = TextToolOutput
  val inputRW = summon[RW[DapStepOverInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("dap_step_over")
  val description =
    """Step over the next statement in the current frame (don't enter nested calls).
      |
      |`sessionId` selects the active session.
      |`threadId` is the thread to step.""".stripMargin
  override val examples = List(
    ToolExample(
      "step over the next line",
      DapStepOverInput(sessionId = "demo-session", threadId = 1)
    )
  )

  override def executeResult(input: DapStepOverInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    withSession(input.sessionId, context) { session =>
      session.next(input.threadId).map(_ =>
        ToolResult.success(TextToolOutput(s"Stepped over on thread ${input.threadId}.")))
    }
}
