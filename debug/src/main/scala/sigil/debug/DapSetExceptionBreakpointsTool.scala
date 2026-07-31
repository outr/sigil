package sigil.debug

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}

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
final class DapSetExceptionBreakpointsTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapSetExceptionBreakpointsInput
  type Output = TextToolOutput
  val inputRW = summon[RW[DapSetExceptionBreakpointsInput]]
  val outputRW = summon[RW[TextToolOutput]]
  override val name = ToolName("dap_set_exception_breakpoints")
  override val description =
    """Configure exception breakpoint filters in an active debug session.
      |
      |`sessionId` selects the active session.
      |`filters` is a list of adapter-defined filter ids (e.g. "uncaught", "all", "raised").
      |Empty list disables exception breakpoints.
      |Returns each filter's verified state and any message the adapter reported.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("debug", "dap", "exception", "breakpoints", "filters", "uncaught"))
  )
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
        if (input.filters.isEmpty) ToolResult.success(TextToolOutput("Cleared exception breakpoints."))
        else {
          val rows = input.filters.zipAll(bps, "", null).collect { case (filter, b) if filter.nonEmpty =>
            val verified = Option(b).exists(_.isVerified)
            val state = if (verified) "verified" else "unverified"
            val msg = Option(b).flatMap(bp => Option(bp.getMessage)).map(m => s" — $m").getOrElse("")
            (filter, verified, s"  $filter: $state$msg")
          }
          val text = rows.map(_._3).mkString("\n")
          if (rows.exists(_._2)) ToolResult.success(TextToolOutput(text))
          else ToolResult.failure(s"No exception filter was verified by the adapter:\n$text")
        }
      }
    }
}
