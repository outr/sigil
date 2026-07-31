package sigil.debug

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{
  DiscoverySpec,
  Effect,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolExample,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

case class DapSetBreakpointsInput(sessionId: String, filePath: String, lines: List[Int]) extends ToolInput derives RW

/**
 * Replace the breakpoints set on a source file. Per the DAP
 * protocol, this is a *replacement* — passing an empty `lines`
 * clears the file's breakpoints. The server returns the verified
 * state for each (some lines may move to the nearest valid statement
 * or be marked unverified if the source isn't loaded yet).
 */
final class DapSetBreakpointsTool(val manager: DapManager) extends Tool with DapToolSupport {
  type Input = DapSetBreakpointsInput
  type Output = TextToolOutput
  val io: ToolIO[DapSetBreakpointsInput, TextToolOutput] = ToolIO.derived[DapSetBreakpointsInput, TextToolOutput].withExamples(
    ToolExample(
      "set two breakpoints",
      DapSetBreakpointsInput(sessionId = "demo-session", filePath = "/abs/path/Foo.scala", lines = List(15, 32))
    )
  )
  override val name = ToolName("dap_set_breakpoints")
  override val description =
    """Set source breakpoints for a file in an active debug session (replaces any prior set).
      |
      |`sessionId` selects the active session.
      |`filePath` is the absolute path.
      |`lines` is the list of 1-based line numbers; empty clears the file's breakpoints.
      |Returns each breakpoint's verified state and any line adjustment the adapter made.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("debug", "dap", "breakpoints", "breakpoint", "set", "source", "line"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: DapSetBreakpointsInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    withSession(input.sessionId, context) { session =>
      session.setBreakpoints(input.filePath, input.lines).map { bps =>
        val text =
          if (bps.isEmpty) s"Cleared breakpoints in ${input.filePath}."
          else bps.zipWithIndex.map { case (b, idx) =>
            val verified = if (b.isVerified) "verified" else "unverified"
            val line = Option(b.getLine).map(_.toString).getOrElse("?")
            val msg = Option(b.getMessage).map(m => s" — $m").getOrElse("")
            s"  [$idx] line $line: $verified$msg"
          }.mkString("\n")
        ToolResult.success(TextToolOutput(text))
      }
    }
}
