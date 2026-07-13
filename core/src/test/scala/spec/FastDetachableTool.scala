package spec

import rapid.Task
import sigil.tool.{TextToolOutput, ToolContext, ToolName, ToolResult}

/** Detachable tool that completes immediately — pins the regression
  * that sub-threshold detachable executions stay fully synchronous,
  * emission-identical to a non-detachable tool. */
case object FastDetachableTool extends SlowStopToolBase {
  val name = ToolName("fast_detachable")
  val description = "Test-only detachable tool that completes instantly."
  override val keywords: Set[String] = Set("fast", "detachable", "test")
  override def detachable: Boolean = true

  override def executeResult(input: SlowStopInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task {
      stepsRun.incrementAndGet()
      ToolResult.Success(TextToolOutput("fast detachable done"))
    }
}
