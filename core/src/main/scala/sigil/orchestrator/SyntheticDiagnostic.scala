package sigil.orchestrator

import lightdb.id.Id
import sigil.conversation.{Conversation, Topic}
import sigil.event.{Event, Message, MessageDisposition, MessageRole, MessageVisibility, ToolInvoke, ToolOutcome}
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
             topicId: Id[Topic],
             input: Option[sigil.tool.ToolInput] = None): ToolInvoke = {
    val syntheticInvokeId = Event.id()
    ToolInvoke(
      toolName       = ToolName.internal(name),
      participantId  = caller,
      conversationId = convId,
      topicId        = topicId,
      _id            = syntheticInvokeId,
      state          = EventState.Complete,
      internal       = true,
      input          = input
    )
  }

  /** Mint the synthetic invoke for a typed [[Directive]] — the wire
    * name comes from the directive and the typed payload rides along
    * as a [[sigil.tool.DirectiveInput]]. */
  def invoke(directive: Directive,
             caller: ParticipantId,
             convId: Id[Conversation],
             topicId: Id[Topic]): ToolInvoke =
    invoke(directive.wireName, caller, convId, topicId,
      Some(sigil.tool.DirectiveInput(directive)))

  /** Build the (synthetic-invoke, paired Tool-role Message) pair for a
    * typed [[Directive]]: name, prose, and typed payload all sourced
    * from the one ADT. */
  def apply(directive: Directive,
            caller: ParticipantId,
            convId: Id[Conversation],
            topicId: Id[Topic],
            disposition: MessageDisposition): List[Signal] =
    build(invoke(directive, caller, convId, topicId), directive.render, disposition)

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
            disposition: MessageDisposition = MessageDisposition.Success): List[Signal] =
    build(invoke(name, caller, convId, topicId), reason, disposition)

  /** Pair a minted synthetic invoke with its Tool-role Message.
    *
    * Sigil #341 — the invoke is made SELF-DESCRIBING by stamping the
    * reason onto its own `outcome` + `summary`. The diagnostic's whole
    * job is to hand the agent its `reason`, but that lived solely in
    * the paired Message — and on the orchestrator's execute-stream emit
    * path that Message wasn't reaching the agent's frame, so the invoke
    * rendered as a content-free `(pending)` and stranded the agent.
    * `FrameBuilder` pairs the Message into the invoke's frame when
    * present and otherwise falls back to the invoke's own
    * outcome/summary, so carrying the reason here means the guidance
    * always reaches the frame. */
  private def build(syntheticInvokeBase: ToolInvoke,
                    reason: String,
                    disposition: MessageDisposition): List[Signal] = {
    val outcome = disposition match {
      case f: MessageDisposition.Failure => ToolOutcome.Failure(reason, f.recoverable)
      case _                             => ToolOutcome.Success
    }
    val syntheticInvoke = syntheticInvokeBase.copy(outcome = outcome, summary = reason)
    val caller = syntheticInvoke.participantId
    val convId = syntheticInvoke.conversationId
    val topicId = syntheticInvoke.topicId
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
