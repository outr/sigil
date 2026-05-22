package sigil.debug

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}

case class DapStepInInput(sessionId: String, threadId: Int) extends ToolInput derives RW

/**
 * Step into a nested method call at the current line. If there's no
 * call at the cursor, behaves like step-over.
 */
final class DapStepInTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapStepInInput
  type Output = TextToolOutput
  val inputRW = summon[RW[DapStepInInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("dap_step_in")
  val description =
    """Step into a nested method call at the current line.
      |
      |`sessionId` selects the active session.
      |`threadId` is the thread to step.""".stripMargin
  override val examples = List(
    ToolExample(
      "step into a method",
      DapStepInInput(sessionId = "demo-session", threadId = 1)
    )
  )
  override def paginate: Boolean = false

  override def executeResult(input: DapStepInInput, context: TurnContext): Task[ToolResult[TextToolOutput]] =
    withSession(input.sessionId, context) { session =>
      session.stepIn(input.threadId).map(_ =>
        ToolResult.success(TextToolOutput(s"Stepped in on thread ${input.threadId}.")))
    }
}
