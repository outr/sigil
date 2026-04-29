package sigil.debug

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class DapThreadsInput(sessionId: String) extends ToolInput derives RW

/**
 * List active threads in the debugged program. The agent uses this
 * to find a thread id for stack-trace / continue / step calls when
 * it doesn't already have one from the latest stop event.
 */
final class DapThreadsTool(val manager: DapManager) extends TypedTool[DapThreadsInput](
  name = ToolName("dap_threads"),
  description =
    """List active threads in the debugged program.
      |
      |`sessionId` selects the active session.
      |Returns each thread's id and name.""".stripMargin,
  examples = List(
    ToolExample(
      "list threads",
      DapThreadsInput(sessionId = "demo-session")
    )
  )
) with DapToolSupport {
  override protected def executeTyped(input: DapThreadsInput, context: TurnContext): Stream[Event] =
    withSession(input.sessionId, context) { session =>
      session.threads.map { threads =>
        if (threads.isEmpty) "No threads."
        else threads.map { t =>
          s"  [${t.getId}] ${t.getName}"
        }.mkString("\n")
      }
    }
}
