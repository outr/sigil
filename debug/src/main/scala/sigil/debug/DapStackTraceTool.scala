package sigil.debug

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class DapStackTraceInput(sessionId: String,
                              threadId: Int,
                              startFrame: Int = 0,
                              levels: Int = 20) extends ToolInput derives RW

/**
 * Fetch the call stack for a stopped thread. Returns each frame's
 * id, name, source path + line, and (when available) a column.
 *
 * The frame id is what the agent passes to `dap_scopes` to inspect
 * locals at that frame.
 */
final class DapStackTraceTool(val manager: DapManager) extends TypedTool[DapStackTraceInput](
  name = ToolName("dap_stack_trace"),
  description =
    """Fetch the call stack for a stopped thread.
      |
      |`sessionId` selects the active session.
      |`threadId` is the thread (typically from the latest stopped event).
      |`startFrame` (default 0) and `levels` (default 20) page through deep stacks.
      |Returns each frame's id, name, source path, and line.""".stripMargin,
  examples = List(
    ToolExample(
      "fetch the top 20 frames",
      DapStackTraceInput(sessionId = "demo-session", threadId = 1)
    )
  )
) with DapToolSupport {
  override protected def executeTyped(input: DapStackTraceInput, context: TurnContext): Stream[Event] =
    withSession(input.sessionId, context) { session =>
      session.stackTrace(input.threadId, input.startFrame, input.levels).map { frames =>
        if (frames.isEmpty) "No frames."
        else frames.map { f =>
          val source = Option(f.getSource).flatMap(s => Option(s.getPath)).getOrElse("(unknown)")
          val line = Option(f.getLine).map(_.toString).getOrElse("?")
          s"  [${f.getId}] ${f.getName} — $source:$line"
        }.mkString("\n")
      }
    }
}
