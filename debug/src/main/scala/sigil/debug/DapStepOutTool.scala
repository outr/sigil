package sigil.debug

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class DapStepOutInput(sessionId: String, threadId: Int) extends ToolInput derives RW

/**
 * Run to the end of the current frame and stop in the caller. The
 * agent uses this to back out of a method when the rest of its
 * execution isn't relevant.
 */
final class DapStepOutTool(val manager: DapManager) extends TypedTool[DapStepOutInput](
  name = ToolName("dap_step_out"),
  description =
    """Run to the end of the current frame and stop in the caller.
      |
      |`sessionId` selects the active session.
      |`threadId` is the thread to step.""".stripMargin,
  examples = List(
    ToolExample(
      "step out of the current method",
      DapStepOutInput(sessionId = "demo-session", threadId = 1)
    )
  )
) with DapToolSupport {
  override protected def executeTyped(input: DapStepOutInput, context: TurnContext): Stream[Event] =
    withSession(input.sessionId, context) { session =>
      session.stepOut(input.threadId).map(_ => s"Stepped out on thread ${input.threadId}.")
    }
}
