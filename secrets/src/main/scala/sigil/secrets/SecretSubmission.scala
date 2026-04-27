package sigil.secrets

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import lightdb.util.Nowish
import sigil.conversation.{Conversation, Topic}
import sigil.event.{Event, MessageRole}
import sigil.participant.ParticipantId
import sigil.security.SecretKind
import sigil.signal.EventState

/**
 * Wire-incoming signal carrying a freshly-typed secret value from the
 * user's UI form (the one rendered for a
 * [[sigil.tool.model.ResponseContent.SecretInput]] content block).
 *
 * `SecretSubmission` is **transient** — it never reaches
 * `SigilDB.events`. The default [[SecretCaptureTransform]] consumes
 * it in `Sigil.inboundTransforms`, writes the value to
 * [[SecretStore]], and replaces it with a `Message` (from the
 * submitter) whose content is a
 * [[sigil.tool.model.ResponseContent.SecretRef]] pointing at the
 * stored id. So:
 *
 *   - The plaintext value lives in the wire frame and the inbound
 *     transform's local memory only — never in events, projections,
 *     wire logs, replays, or LLM context.
 *   - The conversation history records "user submitted secret X"
 *     via the rendered Message, which triggers the agent's loop
 *     normally (Message-from-non-self rule in `TriggerFilter`) so
 *     the agent can react ("Saved.").
 *
 * Apps without `sigil-secrets` loaded simply don't have
 * `SecretCaptureTransform` installed; a `SecretSubmission` arriving
 * on the wire would persist with the value intact, which is the
 * footgun the secrets module exists to prevent.
 */
case class SecretSubmission(secretId: String,
                            value: String,
                            kind: SecretKind,
                            label: String,
                            participantId: ParticipantId,
                            conversationId: Id[Conversation],
                            topicId: Id[Topic],
                            state: EventState = EventState.Active,
                            timestamp: Timestamp = Timestamp(Nowish()),
                            role: MessageRole = MessageRole.Standard,
                            _id: Id[Event] = Event.id())
  extends Event derives RW {
  override def withState(state: EventState): Event = copy(state = state)
}
