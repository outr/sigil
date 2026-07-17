package spec

import rapid.Task
import sigil.tool.{TextToolOutput, ToolContext, ToolName, ToolResult}

import java.util.concurrent.TimeUnit

/**
 * Detachable slow-batch fixture: runs the first half, parks on
 * `proceedLatch` (long enough to cross a spec-tightened detach
 * threshold), reports progress, honors `ctx.checkpoint`, then finishes
 * the batch. Exercises the full detached lifecycle — promotion,
 * post-detach progress, cooperative Stop, and the completion fold.
 */
case object DetachableSweepTool extends SlowStopToolBase {
  val name = ToolName("detachable_sweep")
  val description = "Test-only detachable slow batch tool."
  override val keywords: Set[String] = Set("detachable", "sweep", "test")
  override def detachable: Boolean = true

  override def executeResult(input: SlowStopInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task {
      firstHalf()
      proceedLatch.await(20, TimeUnit.SECONDS)
      ()
    }.flatMap(_ => ctx.reportProgress("resumed after latch"))
      .flatMap(_ => ctx.checkpoint)
      .map(_ => secondHalf())
}
