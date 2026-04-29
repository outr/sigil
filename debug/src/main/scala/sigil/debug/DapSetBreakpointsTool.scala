package sigil.debug

import fabric.rw.*
import rapid.Stream
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}

case class DapSetBreakpointsInput(sessionId: String,
                                  filePath: String,
                                  lines: List[Int]) extends ToolInput derives RW

/**
 * Replace the breakpoints set on a source file. Per the DAP
 * protocol, this is a *replacement* — passing an empty `lines`
 * clears the file's breakpoints. The server returns the verified
 * state for each (some lines may move to the nearest valid statement
 * or be marked unverified if the source isn't loaded yet).
 */
final class DapSetBreakpointsTool(val manager: DapManager) extends TypedTool[DapSetBreakpointsInput](
  name = ToolName("dap_set_breakpoints"),
  description =
    """Set source breakpoints for a file in an active debug session (replaces any prior set).
      |
      |`sessionId` selects the active session.
      |`filePath` is the absolute path.
      |`lines` is the list of 1-based line numbers; empty clears the file's breakpoints.
      |Returns each breakpoint's verified state and any line adjustment the adapter made.""".stripMargin,
  examples = List(
    ToolExample(
      "set two breakpoints",
      DapSetBreakpointsInput(sessionId = "demo-session", filePath = "/abs/path/Foo.scala", lines = List(15, 32))
    )
  )
) with DapToolSupport {
  override protected def executeTyped(input: DapSetBreakpointsInput, context: TurnContext): Stream[Event] =
    withSession(input.sessionId, context) { session =>
      session.setBreakpoints(input.filePath, input.lines).map { bps =>
        if (bps.isEmpty) s"Cleared breakpoints in ${input.filePath}."
        else bps.zipWithIndex.map { case (b, idx) =>
          val verified = if (b.isVerified) "verified" else "unverified"
          val line = Option(b.getLine).map(_.toString).getOrElse("?")
          val msg = Option(b.getMessage).map(m => s" — $m").getOrElse("")
          s"  [$idx] line $line: $verified$msg"
        }.mkString("\n")
      }
    }
}
