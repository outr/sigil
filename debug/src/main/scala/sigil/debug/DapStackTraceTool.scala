package sigil.debug

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Tool, ToolExample, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}

case class DapStackTraceInput(sessionId: String,
                              threadId: Int,
                              startFrame: Int = 0,
                              levels: Int = 20) extends ToolInput derives RW

/**
 * Fetch the call stack for a stopped thread. Returns each frame's
 * id, name, source path + line.
 *
 * The frame id is what the agent passes to `dap_scopes` to inspect
 * locals at that frame.
 */
final class DapStackTraceTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapStackTraceInput
  type Output = DapStackTraceOutput
  val inputRW = summon[RW[DapStackTraceInput]]
  val outputRW = summon[RW[DapStackTraceOutput]]
  override val name = ToolName("dap_stack_trace")
  override val description =
    """Fetch the call stack for a stopped thread.
      |
      |`sessionId` selects the active session.
      |`threadId` is the thread (typically from the latest stopped event).
      |`startFrame` (default 0) and `levels` (default 20) page through deep stacks.
      |Returns each frame's id, name, source path, and line.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Volatile)),
    discovery = DiscoverySpec(keywords = Set("debug", "dap", "stack", "trace", "frames", "callstack"))
  )
  override val examples = List(
    ToolExample(
      "fetch the top 20 frames",
      DapStackTraceInput(sessionId = "demo-session", threadId = 1)
    )
  )

  override def executeResult(input: DapStackTraceInput, context: ToolContext): Task[ToolResult[DapStackTraceOutput]] =
    withSession(input.sessionId, context) { session =>
      session.stackTrace(input.threadId, input.startFrame, input.levels).map { frames =>
        ToolResult.success(DapStackTraceOutput(
          frames.map { f =>
            DapStackFrameInfo(
              id = f.getId,
              name = f.getName,
              source = Option(f.getSource).flatMap(s => Option(s.getPath)),
              line = Option(f.getLine).map(_.intValue)
            )
          }
        ))
      }
    }
}
