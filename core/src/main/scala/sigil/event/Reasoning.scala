package sigil.event

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.participant.ParticipantId
import sigil.signal.EventState

/**
 * Provider-internal reasoning state captured during a streaming LLM
 * response. Bug #61 — required by OpenAI's Responses API for the
 * gpt-5 / gpt-5.x / o1 / o3 family: each turn that exercises a
 * reasoning model emits one or more `reasoning` output items
 * (id `rs_…`, plus a summary and optionally an encrypted CoT blob)
 * which the API expects to find verbatim in the next request's
 * `input` array, in their original positions relative to function
 * calls. Omit them and the next response is a silent empty stream
 * (or a 400 with "Reasoning items must be included…").
 *
 * **Lifecycle.** Persisted to `SigilDB.events` like every other
 * Event so it survives restarts and reconnects. The originating
 * agent's prompt-building reads them back via the standard
 * [[sigil.conversation.ContextFrame]] pipeline; the OpenAI provider
 * pulls them out of the rendered message vector and weaves them
 * into the wire `input` array. Non-OpenAI providers see the
 * corresponding [[sigil.provider.ProviderMessage.Reasoning]] entry
 * in their input vector and drop it — reasoning state is
 * provider-specific opacity, not a general conversation artifact.
 *
 * **Visibility.** Default
 * [[sigil.event.MessageVisibility.Participants]] keyed on
 * `participantId` — only the agent that produced the reasoning sees
 * it on the wire and in its own prompt context. Other agents in the
 * conversation (multi-agent setups) and human user UIs both filter
 * it out via `Sigil.canSee`.
 *
 * **No frame on the user-visible projection.**
 * [[sigil.conversation.FrameBuilder]] still emits a
 * `ContextFrame.Reasoning` so the originating agent's prompt-rebuild
 * sees the entry, but the per-agent visibility filter applied in
 * `Sigil.buildContext` drops it for everyone other than the owner.
 *
 * @param providerItemId the wire-level id (e.g. `rs_…`) the provider
 *                       expects in subsequent requests. Distinct from
 *                       Sigil's `_id` (a typed `Id[Event]`).
 * @param summary        zero or more summary-text fragments the
 *                       provider chose to expose. gpt-5 / 5.x ship
 *                       summaries; o1 / o3 typically rely on
 *                       `encryptedContent` and leave `summary` empty.
 * @param encryptedContent opaque CoT payload (o1 / o3). Stored
 *                         verbatim and replayed verbatim — Sigil
 *                         never decodes it.
 */
case class Reasoning(providerItemId: String,
                     summary: List[String],
                     encryptedContent: Option[String],
                     participantId: ParticipantId,
                     conversationId: Id[Conversation],
                     topicId: Id[Topic],
                     state: EventState = EventState.Complete,
                     timestamp: Timestamp = Timestamp(Nowish()),
                     role: MessageRole = MessageRole.Standard,
                     override val visibility: MessageVisibility =
                       MessageVisibility.Participants(Set.empty),
                     override val origin: Option[Id[Event]] = None,
                     _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
  override def withOrigin(origin: Option[Id[Event]]): Event = copy(origin = origin)
}
