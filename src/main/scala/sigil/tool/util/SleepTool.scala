package sigil.tool.util

import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolName, TypedTool}
import sigil.tool.model.SleepInput

import scala.concurrent.duration.DurationLong

/**
 * Pause for the given number of milliseconds, then return without
 * emitting any events.
 */
case object SleepTool extends TypedTool[SleepInput](
  name = ToolName("sleep"),
  description =
    """Pause for the given number of milliseconds. Use when you need to wait — between polling attempts,
      |to avoid hammering an external API, or to give an async side effect time to settle before the next
      |action.
      |
      |Emits no events. The conversation resumes from whatever your next call is.""".stripMargin,
  examples = List(
    ToolExample("Brief pause (500 ms)", SleepInput(500)),
    ToolExample("Back-off before retry (2 seconds)", SleepInput(2000))
  ),
  keywords = Set("sleep", "wait", "delay", "pause")
) {
  override protected def executeTyped(input: SleepInput, context: TurnContext): rapid.Stream[Event] =
    rapid.Stream.force(
      rapid.Task.sleep(input.millis.millis).map(_ => rapid.Stream.empty[Event])
    )
}
