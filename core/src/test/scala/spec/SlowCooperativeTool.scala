package spec

import rapid.Task
import sigil.tool.{
  DiscoverySpec,
  Effect,
  MutationTargeting,
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
 * Cooperates with Stop: calls `ctx.checkpoint` between the batch's
 * halves, so a Stop published while it is paused midway cancels the
 * remaining steps with a visible failure.
 */
case object SlowCooperativeTool extends SlowStopToolBase {
  override val name = ToolName("slow_cooperative")
  override val description = "Test-only slow batch tool that checkpoints for Stop between steps."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("slow", "cooperative", "test"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: SlowStopInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task {
      firstHalf()
      proceedLatch.await(10, TimeUnit.SECONDS)
      ()
    }.flatMap(_ => ctx.checkpoint)
      .map(_ => secondHalf())
}
