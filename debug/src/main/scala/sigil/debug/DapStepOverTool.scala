package sigil.debug

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class DapStepOverInput(sessionId: String, threadId: Int) extends ToolInput derives RW

/**
 * Execute the next statement in the current frame, stepping over
 * any nested method calls. The classic "next" debugger command.
 */
final class DapStepOverTool(val manager: DapManager) extends TypedTool[DapStepOverInput](
  name = ToolName("dap_step_over"),
  description =
    """Step over the next statement in the current frame (don't enter nested calls).
      |
      |`sessionId` selects the active session.
      |`threadId` is the thread to step.""".stripMargin,
  examples = List(
    ToolExample(
      "step over the next line",
      DapStepOverInput(sessionId = "demo-session", threadId = 1)
    )
  )
) with DapToolSupport {
  override protected def executeTyped(input: DapStepOverInput, context: TurnContext): Stream[Event] =
    withSession(input.sessionId, context) { session =>
      session.next(input.threadId).map(_ => s"Stepped over on thread ${input.threadId}.")
    }
}
