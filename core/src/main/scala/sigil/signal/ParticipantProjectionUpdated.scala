package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.{Conversation, ParticipantProjection}
import sigil.participant.ParticipantId

/**
 * Sigil #291 — server→client [[Notice]] broadcast when a
 * [[ParticipantProjection]] is mutated through
 * [[sigil.Sigil.updateProjection]]. Carries the post-write
 * projection so subscribers can replace their cached entry
 * wholesale rather than diffing.
 *
 * Closes the multi-client polling gap: when one client mutates
 * per-conversation app state (active preview target, in-flight
 * skill activation, extraContext keys), other clients subscribed
 * to the same conversation see the change without a `/status`
 * poll. The framework's existing WebSocket fanout (signalsFor)
 * delivers this Notice to every subscribed viewer.
 *
 * Distinct from [[ParticipantUpdated]]: that one carries identity
 * changes (avatar / display name on the [[sigil.participant.Participant]]
 * record); this one carries per-conversation projection state
 * (active skills, suggested tools, extraContext, recent tool
 * invocations).
 *
 * Suppressible at the write site via `updateProjection(..., broadcast
 * = false)` for framework-internal cache writes that no UI cares
 * about (e.g. cached `previous_response_id` for provider streaming).
 */
case class ParticipantProjectionUpdated(conversationId: Id[Conversation],
                                         participantId: ParticipantId,
                                         projection: ParticipantProjection) extends ConversationNotice derives RW
