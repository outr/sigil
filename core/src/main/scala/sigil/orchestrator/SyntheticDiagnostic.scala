package sigil.orchestrator

import lightdb.id.Id
import sigil.conversation.{Conversation, Topic}
import sigil.event.{Event, Message, MessageDisposition, MessageRole, MessageVisibility, ToolInvoke}
import sigil.participant.ParticipantId
import sigil.signal.{EventState, Signal}
import sigil.tool.ToolName
import sigil.tool.model.ResponseContent

/**
 * Shared constructor for the framework's synthetic internal diagnostics.
 *
 * Several orchestrator / agent-loop paths surface a model-correction
 * signal by minting a synthetic `internal = true` [[ToolInvoke]] (names
 * like `_refusal_challenge`, `_repeated_query_intercept`,
 * `_plain_text_reply`, `_degenerate_generation`, `_provider_error`,
 * `_stall_detected`, `_cap_reached`) and pairing it with a Tool-role
 * `Message` whose `origin` links the two. The pairing keeps the
 * conversation's frame trail well-formed and the Tool-role tag re-fires
 * the agent's loop so it reads the diagnostic on its next iteration.
 *
 * The synthetic-invoke name doubles as a marker some detectors walk for
 * to enforce a once-per-user-turn intervention limit.
 */
object SyntheticDiagnostic {

  /** Mint the synthetic `internal = true` `ToolInvoke` that parents a
    * diagnostic Message. Callers that build a bespoke Message (a copy of
    * an existing intervention, a non-Failure directive) use this and
    * stamp `origin = Some(invoke._id)` themselves; callers that want the
    * standard Failure-message pairing use [[apply]]. */
  def invoke(name: String,
             caller: ParticipantId,
             convId: Id[Conversation],
             topicId: Id[Topic]): ToolInvoke = {
    val syntheticInvokeId = Event.id()
    ToolInvoke(
      toolName       = ToolName(name),
      participantId  = caller,
      conversationId = convId,
      topicId        = topicId,
      _id            = syntheticInvokeId,
      state          = EventState.Complete,
      internal       = true
    )
  }

  /** Build the (synthetic-invoke, paired Tool-role Message) signal pair.
    * The Message carries `MessageVisibility.Agents` so the diagnostic
    * never leaks to user-facing viewers; `disposition` defaults to
    * `Success` (the `_provider_error` shape) — callers surfacing a
    * recoverable failure pass `MessageDisposition.Failure(...)`. */
  def apply(name: String,
            caller: ParticipantId,
            convId: Id[Conversation],
            topicId: Id[Topic],
            reason: String,
            disposition: MessageDisposition = MessageDisposition.Success): List[Signal] = {
    val syntheticInvoke = invoke(name, caller, convId, topicId)
    val diagnostic = Message(
      participantId  = caller,
      conversationId = convId,
      topicId        = topicId,
      role           = MessageRole.Tool,
      content        = Vector(ResponseContent.Text(reason)),
      disposition    = disposition,
      state          = EventState.Complete,
      visibility     = MessageVisibility.Agents,
      origin         = Some(syntheticInvoke._id)
    )
    List[Signal](syntheticInvoke, diagnostic)
  }
}
