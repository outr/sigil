package spec

import rapid.Task
import sigil.tool.{DiscoverySpec, Effect, Execution, MutationTargeting, ProgressContract, TextToolOutput, ToolContext, ToolName, ToolProfile, ToolResult, ToolSpec}

/** Detachable tool that completes immediately — pins the regression
  * that sub-threshold detachable executions stay fully synchronous,
  * emission-identical to a non-detachable tool. */
case object FastDetachableTool extends SlowStopToolBase {
  override val name = ToolName("fast_detachable")
  override val description = "Test-only detachable tool that completes instantly."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(
      effect = Effect.Mutating(MutationTargeting.none),
      execution = Execution.Detachable(keepRunningOnStop = false, progress = ProgressContract("test fixture progress"))
    ),
    discovery = DiscoverySpec(keywords = Set("fast", "detachable", "test"))
  )

  override def executeResult(input: SlowStopInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task {
      stepsRun.incrementAndGet()
      ToolResult.Success(TextToolOutput("fast detachable done"))
    }
}
