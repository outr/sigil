package spec

import fabric.rw.*
import rapid.Task
import sigil.tool.{TextToolOutput, Tool, ToolName, ToolResult}
import sigil.tool.ToolContext

import java.util.concurrent.CountDownLatch

/**
 * Test-only tool whose `executeResult` blocks on [[EagerActiveLatchTool.releaseLatch]]
 * until the spec releases it. Drives #317 coverage: the agent loop's
 * batched-events drain can't complete until this tool finishes, so a
 * `ToolInvoke(Active)` that only reaches the wire at drain-end would be
 * invisible for the whole block. The spec counts down [[startedLatch]] from
 * inside the body so it can assert the tool is genuinely mid-execution
 * before checking what the wire client received.
 */
case object EagerActiveLatchTool extends Tool {
  type Input = EagerActiveLatchInput
  type Output = TextToolOutput
  val inputRW = summon[RW[EagerActiveLatchInput]]
  val outputRW = summon[RW[TextToolOutput]]

  val name = ToolName("eager_active_latch")
  val description = "Test-only tool that blocks until the spec releases its latch."
  override val keywords: Set[String] = Set("latch", "test", "blocking")

  /**
   * Released by the spec to let a blocked execution finish. Reassigned
   * per scenario via [[reset]].
   */
  @volatile var releaseLatch: CountDownLatch = new CountDownLatch(1)

  /**
   * Counted down by the tool body the instant execution begins, so the
   * spec knows the dispatch reached the tool (and is now blocked).
   */
  @volatile var startedLatch: CountDownLatch = new CountDownLatch(1)

  def reset(): Unit = {
    releaseLatch = new CountDownLatch(1)
    startedLatch = new CountDownLatch(1)
  }

  override def executeResult(input: EagerActiveLatchInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task {
      startedLatch.countDown()
      releaseLatch.await()
      ToolResult.Success(TextToolOutput("released"))
    }
}
