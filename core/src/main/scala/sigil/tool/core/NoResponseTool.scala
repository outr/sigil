package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, Message}
import sigil.signal.EventState
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.{NoResponseInput, ResponseContent}

/**
 * Lets the agent end its turn without producing any user-visible content.
 *
 * In a multi-participant chat-based model, an agent may be activated on events
 * that aren't directed at it. Rather than forcing a response, the agent calls
 * `no_response` to signal "nothing from me this turn."
 *
 * Bug #79 — when small/mid models pick `no_response` and cram a refusal /
 * apology into `reason` (e.g. *"I don't have the ability to switch models…"*),
 * the framework auto-promotes to a `respond` Message rather than swallowing
 * the user-directed prose. The agent's intent stays delivered; the user
 * doesn't get silence after the model produced a deliverable message.
 */
case object NoResponseTool extends TypedTool[NoResponseInput](
  name = ToolName("no_response"),
  description =
    """Decline to respond — when the message isn't for you, or no reply is warranted. Cleaner than
      |`respond` with filler like "nothing to add".
      |
      |`reason` is OPTIONAL audit / telemetry only — it is NOT shown to the user. Keep it short
      |(<120 chars), in third-person debug shape ("triggered by stop event", "off-topic for this
      |agent"). If you have something to SAY to the user — even a refusal or apology — call
      |`respond` instead; user-directed prose stuffed into `reason` is auto-promoted to a
      |respond and will trigger a warning.""".stripMargin
) with RespondFamilyTool {
  override def paginate: Boolean = false

  override protected def executeTyped(input: NoResponseInput, context: TurnContext): rapid.Stream[Event] =
    input.reason match {
      case Some(reason) if isUserDirectedProse(reason) =>
        scribe.warn(s"NoResponseTool: auto-promoting prose-shaped reason to a respond Message — " +
          s"the agent should call respond directly for user-directed text. Reason: ${reason.take(80)}…")
        rapid.Stream.emit[Event](Message(
          participantId  = context.caller,
          conversationId = context.conversation.id,
          topicId        = context.conversation.currentTopicId,
          content        = Vector(ResponseContent.Markdown(reason)),
          state          = EventState.Complete,
          modelId        = context.modelId
        ))
      case _ =>
        rapid.Stream.empty
    }

  /** Heuristic: a `reason` with any of the following looks like
    * user-directed prose mis-routed into `no_response`, not a debug
    * breadcrumb:
    *   - longer than 120 characters
    *   - starts with first-person / apology / refusal phrasing
    *   - contains a sentence boundary (`. ` followed by a capital)
    *
    * Conservative: short third-person debug reasons ("off-topic",
    * "stop signal received") pass through unchanged. */
  private def isUserDirectedProse(reason: String): Boolean = {
    val text = reason.trim
    if (text.length > 120) return true
    val starts = List(
      "I ", "I'm ", "I'd ", "I've ", "I cannot", "I can't", "I don't",
      "I do not", "I'm not", "I am not",
      "Sorry", "Unfortunately", "Apologies", "My apolog",
      "Hi", "Hello", "Hey",
      "It seems", "It looks like", "You can", "You'll need", "You should"
    )
    if (starts.exists(text.startsWith)) return true
    val sentenceBoundary = "[.!?]\\s+[A-Z]".r
    sentenceBoundary.findFirstIn(text).isDefined
  }
}
