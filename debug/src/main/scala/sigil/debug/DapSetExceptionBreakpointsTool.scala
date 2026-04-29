package sigil.debug

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class DapSetExceptionBreakpointsInput(sessionId: String,
                                           filters: List[String]) extends ToolInput derives RW

/**
 * Configure exception filters — pause execution when an exception
 * matching one of the given filters is thrown / uncaught.
 *
 * Filter ids are adapter-specific. JVM adapters typically support
 * `"all"`, `"uncaught"`, and named subclasses; Python's debugpy
 * supports `"raised"`, `"uncaught"`, `"userUnhandled"`. Empty list
 * disables exception breakpoints entirely.
 */
final class DapSetExceptionBreakpointsTool(val manager: DapManager) extends TypedTool[DapSetExceptionBreakpointsInput](
  name = ToolName("dap_set_exception_breakpoints"),
  description =
    """Configure exception breakpoint filters in an active debug session.
      |
      |`sessionId` selects the active session.
      |`filters` is a list of adapter-defined filter ids (e.g. "uncaught", "all", "raised").
      |Empty list disables exception breakpoints.""".stripMargin,
  examples = List(
    ToolExample(
      "break on uncaught exceptions",
      DapSetExceptionBreakpointsInput(sessionId = "demo-session", filters = List("uncaught"))
    )
  )
) with DapToolSupport {
  override protected def executeTyped(input: DapSetExceptionBreakpointsInput, context: TurnContext): Stream[Event] =
    withSession(input.sessionId, context) { session =>
      session.setExceptionBreakpoints(input.filters).map { bps =>
        if (input.filters.isEmpty) "Cleared exception breakpoints."
        else s"Exception breakpoints set: ${input.filters.mkString(", ")} (${bps.size} state entries returned)."
      }
    }
}
