package sigil.debug

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolExample, ToolInput, ToolName, ToolResult}

case class DapThreadsInput(sessionId: String) extends ToolInput derives RW

/**
 * List active threads in the debugged program. The agent uses this
 * to find a thread id for stack-trace / continue / step calls when
 * it doesn't already have one from the latest stop event.
 */
final class DapThreadsTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapThreadsInput
  type Output = DapThreadsOutput
  val inputRW = summon[RW[DapThreadsInput]]
  val outputRW = summon[RW[DapThreadsOutput]]
  val name = ToolName("dap_threads")
  val description =
    """List active threads in the debugged program.
      |
      |`sessionId` selects the active session.
      |Returns each thread's id and name.""".stripMargin
  override val examples = List(
    ToolExample(
      "list threads",
      DapThreadsInput(sessionId = "demo-session")
    )
  )

  override def executeResult(input: DapThreadsInput, context: ToolContext): Task[ToolResult[DapThreadsOutput]] =
    withSession(input.sessionId, context) { session =>
      session.threads.map { threads =>
        ToolResult.success(DapThreadsOutput(
          threads.map(t => DapThreadInfo(id = t.getId, name = t.getName))
        ))
      }
    }
}
