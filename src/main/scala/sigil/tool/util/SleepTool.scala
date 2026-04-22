package sigil.tool.util

import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{Tool, ToolExample}
import sigil.tool.model.SleepInput

import scala.concurrent.duration.DurationLong

/**
 * Pause for the given number of milliseconds, then return without
 * emitting any events. Useful for:
 *
 *   - Pacing — polling a resource with a delay between attempts
 *   - Rate limiting — spacing out external API calls
 *   - Deliberate delays in multi-step workflows (waiting for an async
 *     side effect to settle before the next step)
 *
 * Atomic. Emits nothing on completion; the turn continues with whatever
 * the next tool call or respond decides.
 */
object SleepTool extends Tool[SleepInput] {
  override protected def uniqueName: String = "sleep"

  override protected def description: String =
    """Pause for the given number of milliseconds. Use when you need to wait — between polling attempts,
      |to avoid hammering an external API, or to give an async side effect time to settle before the next
      |action.
      |
      |Emits no events. The conversation resumes from whatever your next call is.""".stripMargin

  override protected def examples: List[ToolExample[SleepInput]] = List(
    ToolExample("Brief pause (500 ms)", SleepInput(500)),
    ToolExample("Back-off before retry (2 seconds)", SleepInput(2000))
  )

  override def execute(input: SleepInput, context: TurnContext): rapid.Stream[Event] =
    rapid.Stream.force(
      rapid.Task.sleep(input.millis.millis).map(_ => rapid.Stream.empty[Event])
    )
}
