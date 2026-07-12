package spec

import rapid.Task
import sigil.tool.{TextToolOutput, ToolContext, ToolName, ToolResult}

import java.util.concurrent.TimeUnit

/** Ignores Stop entirely — no checkpoint, and it shrugs off the thread
  * interrupt a force-Stop's drain cancellation delivers — reproducing
  * the observed field behavior of a sweep that grinds to completion
  * after the user stopped the agent. The invoke must STILL end settled. */
case object SlowStubbornTool extends SlowStopToolBase {
  val name = ToolName("slow_stubborn")
  val description = "Test-only slow batch tool that ignores Stop and runs to completion."
  override val keywords: Set[String] = Set("slow", "stubborn", "test")

  override def executeResult(input: SlowStopInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task {
      firstHalf()
      try proceedLatch.await(10, TimeUnit.SECONDS)
      catch { case _: InterruptedException => () } // survives the force-Stop interrupt
      secondHalf()
    }
}
