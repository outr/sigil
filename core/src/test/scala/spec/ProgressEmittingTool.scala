package spec

import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolName, TypedTool}

/** Test-only tool that publishes three [[sigil.signal.ToolProgress]]
  * pulses (one indeterminate, two with `percent`) before completing.
  * Used by Bug #7 coverage to verify the orchestrator stamps
  * `currentToolInvokeId` on the dispatched [[TurnContext]] and that
  * `reportProgress` lands on the conversation's signal stream with
  * the right correlation id and tool attribution. */
case object ProgressEmittingTool extends TypedTool[ToolProgressInput](
  name = ToolName("progress_emitter"),
  description = "Test-only tool that emits ToolProgress pulses while running.",
  keywords = Set("progress", "test")
) {
  override protected def executeTyped(input: ToolProgressInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
      ctx.reportProgress("preparing")
        .flatMap(_ => ctx.reportProgress("halfway", percent = Some(0.5)))
        .flatMap(_ => ctx.reportProgress("almost done", percent = Some(0.9)))
        .map(_ => Stream.empty[Event])
    )
}
