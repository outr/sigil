package spec

import rapid.Task
import sigil.tool.{TextToolOutput, ToolContext, ToolName, ToolResult}

import java.util.concurrent.TimeUnit

/** Cooperates with Stop: calls `ctx.checkpoint` between the batch's
  * halves, so a Stop published while it is paused midway cancels the
  * remaining steps with a visible failure. */
case object SlowCooperativeTool extends SlowStopToolBase {
  val name = ToolName("slow_cooperative")
  val description = "Test-only slow batch tool that checkpoints for Stop between steps."
  override val keywords: Set[String] = Set("slow", "cooperative", "test")

  override def executeResult(input: SlowStopInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task {
      firstHalf()
      proceedLatch.await(10, TimeUnit.SECONDS)
      ()
    }.flatMap(_ => ctx.checkpoint)
      .map(_ => secondHalf())
}
