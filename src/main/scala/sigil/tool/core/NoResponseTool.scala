package sigil.tool.core

import sigil.conversation.Conversation
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.tool.Tool
import sigil.tool.model.NoResponseInput

/**
 * Lets the agent end its turn without producing any user-visible content.
 *
 * In a multi-participant chat-based model, an agent may be activated on events
 * that aren't directed at it. Rather than forcing a response, the agent calls
 * `no_response` to signal "nothing from me this turn."
 *
 * Emits no events — the conversation state is unchanged by this call. An
 * orchestrator that cares whether an activation declined can inspect the
 * ToolCallComplete event in the stream.
 */
object NoResponseTool extends Tool[NoResponseInput] {
  override protected def uniqueName: String = "no_response"

  override protected def description: String =
    """Decline to respond to the current activation. Call this when the latest message isn't directed at you,
      |is better handled by another participant, or otherwise doesn't require a response per your personality.
      |
      |Prefer `no_response` over calling `respond` with filler like "I don't have anything to add" — silent
      |decline is cleaner for the user.""".stripMargin

  override def execute(input: NoResponseInput,
                       caller: ParticipantId,
                       conversation: Conversation): rapid.Stream[Event] = rapid.Stream.empty
}
