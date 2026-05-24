package sigil.tool.util

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.model.SleepInput
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolName, ToolResult}

import scala.concurrent.duration.DurationLong

/**
 * Pause for the given number of milliseconds, then resolve with a
 * confirmation that the pause completed.
 */
case object SleepTool extends Tool {
  type Input  = SleepInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[SleepInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("sleep")
  val description =
    """Pause for the given number of milliseconds. Use when you need to wait — between polling attempts,
      |to avoid hammering an external API, or to give an async side effect time to settle before the next
      |action.
      |
      |The conversation resumes from whatever your next call is.""".stripMargin
  override val examples = List(
    ToolExample("Brief pause (500 ms)", SleepInput(500)),
    ToolExample("Back-off before retry (2 seconds)", SleepInput(2000))
  )
  override val keywords = Set("sleep", "wait", "delay", "pause")

  override def executeResult(input: SleepInput,
                             context: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.sleep(input.millis.millis).map { _ =>
      ToolResult.Success(TextToolOutput(s"Paused for ${input.millis} ms."))
    }
}
