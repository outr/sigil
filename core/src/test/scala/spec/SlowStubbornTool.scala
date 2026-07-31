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
 * Ignores Stop entirely — no checkpoint, and it shrugs off the thread
 * interrupt a force-Stop's drain cancellation delivers — reproducing
 * the observed field behavior of a sweep that grinds to completion
 * after the user stopped the agent. The invoke must STILL end settled.
 */
case object SlowStubbornTool extends SlowStopToolBase {
  override val name = ToolName("slow_stubborn")
  override val description = "Test-only slow batch tool that ignores Stop and runs to completion."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("slow", "stubborn", "test"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: SlowStopInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task {
      firstHalf()
      try proceedLatch.await(10, TimeUnit.SECONDS)
      catch { case _: InterruptedException => () } // survives the force-Stop interrupt
      secondHalf()
    }
}
