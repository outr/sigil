package spec

import rapid.Task
import sigil.tool.{
  DiscoverySpec,
  Effect,
  Execution,
  MutationTargeting,
  ProgressContract,
  Resolution,
  TextToolOutput,
  ToolContext,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}

import java.util.concurrent.TimeUnit

/**
 * Detachable slow-batch fixture: runs the first half, parks on
 * `proceedLatch` (long enough to cross a spec-tightened detach
 * threshold), reports progress, honors `ctx.checkpoint`, then finishes
 * the batch. Exercises the full detached lifecycle — promotion,
 * post-detach progress, cooperative Stop, and the completion fold.
 */
case object DetachableSweepTool extends SlowStopToolBase {
  override val name = ToolName("detachable_sweep")
  override val description = "Test-only detachable slow batch tool."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(
      effect = Effect.Mutating(MutationTargeting.none),
      execution = Execution.Detachable(keepRunningOnStop = false, progress = ProgressContract("test fixture progress"))
    ),
    discovery = DiscoverySpec(keywords = Set("detachable", "sweep", "test"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: SlowStopInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task {
      firstHalf()
      proceedLatch.await(20, TimeUnit.SECONDS)
      ()
    }.flatMap(_ => ctx.reportProgress("resumed after latch"))
      .flatMap(_ => ctx.checkpoint)
      .map(_ => secondHalf())
}
