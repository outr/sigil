package spec

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{TextToolOutput, Tool, ToolName, ToolResult}

/** Test-only tool that publishes three [[sigil.signal.ToolProgress]]
  * pulses (one indeterminate, two with `percent`) before completing.
  * Used by Bug #7 coverage to verify the orchestrator stamps
  * `currentToolInvokeId` on the dispatched [[TurnContext]] and that
  * `reportProgress` lands on the conversation's signal stream with
  * the right correlation id and tool attribution. */
case object ProgressEmittingTool extends Tool {
  type Input  = ToolProgressInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[ToolProgressInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("progress_emitter")
  val description = "Test-only tool that emits ToolProgress pulses while running."
  override val keywords: Set[String] = Set("progress", "test")

  override def executeResult(input: ToolProgressInput, ctx: TurnContext): Task[ToolResult[TextToolOutput]] =
    ctx.reportProgress("preparing")
      .flatMap(_ => ctx.reportProgress("halfway", percent = Some(0.5)))
      .flatMap(_ => ctx.reportProgress("almost done", percent = Some(0.9)))
      .map(_ => ToolResult.Success(TextToolOutput("done")))
}
