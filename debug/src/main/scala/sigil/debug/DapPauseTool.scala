package sigil.debug

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class DapPauseInput(sessionId: String, threadId: Int) extends ToolInput derives RW

/**
 * Pause a running thread. Useful when the program has entered a
 * loop or stuck state and the agent wants to inspect why.
 */
final class DapPauseTool(val manager: DapManager) extends TypedTool[DapPauseInput](
  name = ToolName("dap_pause"),
  description =
    """Pause a running thread.
      |
      |`sessionId` selects the active session.
      |`threadId` is the thread to pause.""".stripMargin,
  examples = List(
    ToolExample(
      "pause a thread",
      DapPauseInput(sessionId = "demo-session", threadId = 1)
    )
  )
) with DapToolSupport {
  override protected def executeTyped(input: DapPauseInput, context: TurnContext): Stream[Event] =
    withSession(input.sessionId, context) { session =>
      session.pause(input.threadId).map(_ => s"Pause requested on thread ${input.threadId}.")
    }
}
