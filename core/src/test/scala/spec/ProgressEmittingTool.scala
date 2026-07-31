package spec

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{
  DiscoverySpec,
  Effect,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolIO,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}
import sigil.tool.ToolContext

/**
 * Test-only tool that publishes three [[sigil.signal.ToolProgress]]
 * pulses (one indeterminate, two with `percent`) before completing.
 * Used by Bug #7 coverage to verify the orchestrator stamps
 * `currentToolInvokeId` on the dispatched [[TurnContext]] and that
 * `reportProgress` lands on the conversation's signal stream with
 * the right correlation id and tool attribution.
 */
case object ProgressEmittingTool extends Tool {
  type Input = ToolProgressInput
  type Output = TextToolOutput
  val io: ToolIO[ToolProgressInput, TextToolOutput] = ToolIO.derived[ToolProgressInput, TextToolOutput]

  override val name = ToolName("progress_emitter")
  override val description = "Test-only tool that emits ToolProgress pulses while running."
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("progress", "test"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: ToolProgressInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    ctx.reportProgress("preparing")
      .flatMap(_ => ctx.reportProgress("halfway", percent = Some(0.5)))
      .flatMap(_ => ctx.reportProgress("almost done", percent = Some(0.9)))
      .map(_ => ToolResult.Success(TextToolOutput("done")))
}
