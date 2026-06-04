package sigil.debug

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}

case class DapSetExceptionBreakpointsInput(sessionId: String,
                                           filters: List[String])
  extends ToolInput derives RW

/**
 * Configure exception filters — pause execution when an exception
 * matching one of the given filters is thrown / uncaught.
 *
 * Filter ids are adapter-specific. JVM adapters typically support
 * `"all"`, `"uncaught"`, and named subclasses; Python's debugpy
 * supports `"raised"`, `"uncaught"`, `"userUnhandled"`. Empty list
 * disables exception breakpoints entirely.
 */
final class DapSetExceptionBreakpointsTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapSetExceptionBreakpointsInput
  type Output = TextToolOutput
  val inputRW = summon[RW[DapSetExceptionBreakpointsInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("dap_set_exception_breakpoints")
  val description =
    """Configure exception breakpoint filters in an active debug session.
      |
      |`sessionId` selects the active session.
      |`filters` is a list of adapter-defined filter ids (e.g. "uncaught", "all", "raised").
      |Empty list disables exception breakpoints.""".stripMargin
  override val examples = List(
    ToolExample(
      "break on uncaught exceptions",
      DapSetExceptionBreakpointsInput(sessionId = "demo-session", filters = List("uncaught"))
    )
  )

  override def executeResult(input: DapSetExceptionBreakpointsInput,
                             context: ToolContext): Task[ToolResult[TextToolOutput]] =
    withSession(input.sessionId, context) { session =>
      session.setExceptionBreakpoints(input.filters).map { bps =>
        val text =
          if (input.filters.isEmpty) "Cleared exception breakpoints."
          else s"Exception breakpoints set: ${input.filters.mkString(", ")} (${bps.size} state entries returned)."
        ToolResult.success(TextToolOutput(text))
      }
    }
}
