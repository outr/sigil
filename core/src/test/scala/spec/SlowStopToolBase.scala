package spec

import fabric.rw.*
import sigil.tool.{TextToolOutput, Tool, ToolResult}

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared scaffolding for the slow-tool Stop fixtures: a 6-step "batch"
 * execution that pauses midway (after step 2) so the spec can publish a
 * Stop at a deterministic point, then resumes when the spec releases it.
 */
trait SlowStopToolBase extends Tool {
  type Input = SlowStopInput
  type Output = TextToolOutput
  val inputRW = summon[RW[SlowStopInput]]
  val outputRW = summon[RW[TextToolOutput]]

  /**
   * Steps actually executed — the spec's "did the work stop?" probe.
   */
  val stepsRun: AtomicInteger = new AtomicInteger(0)

  /**
   * Counted down by the tool after step 2 — the spec's cue to Stop.
   */
  @volatile var midwayLatch: CountDownLatch = new CountDownLatch(1)

  /**
   * Released by the spec once its Stop has been published.
   */
  @volatile var proceedLatch: CountDownLatch = new CountDownLatch(1)

  def reset(): Unit = {
    stepsRun.set(0)
    midwayLatch = new CountDownLatch(1)
    proceedLatch = new CountDownLatch(1)
  }

  protected def firstHalf(): Unit = {
    (1 to 2).foreach(_ => stepsRun.incrementAndGet())
    midwayLatch.countDown()
  }

  protected def secondHalf(): ToolResult[TextToolOutput] = {
    (3 to 6).foreach(_ => stepsRun.incrementAndGet())
    ToolResult.Success(TextToolOutput(s"completed ${stepsRun.get()} steps"))
  }
}
