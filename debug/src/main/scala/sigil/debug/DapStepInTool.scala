package sigil.debug

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class DapStepInInput(sessionId: String, threadId: Int) extends ToolInput derives RW

/**
 * Step into a nested method call at the current line. If there's no
 * call at the cursor, behaves like step-over.
 */
final class DapStepInTool(val manager: DapManager) extends TypedTool[DapStepInInput](
  name = ToolName("dap_step_in"),
  description =
    """Step into a nested method call at the current line.
      |
      |`sessionId` selects the active session.
      |`threadId` is the thread to step.""".stripMargin,
  examples = List(
    ToolExample(
      "step into a method",
      DapStepInInput(sessionId = "demo-session", threadId = 1)
    )
  )
) with DapToolSupport {
  override protected def executeTyped(input: DapStepInInput, context: TurnContext): Stream[Event] =
    withSession(input.sessionId, context) { session =>
      session.stepIn(input.threadId).map(_ => s"Stepped in on thread ${input.threadId}.")
    }
}
