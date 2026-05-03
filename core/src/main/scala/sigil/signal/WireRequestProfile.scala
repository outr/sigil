package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.diagnostics.RequestProfile
import sigil.participant.ParticipantId

/**
 * Diagnostic notice emitted just before a provider call when
 * `Sigil.profileWireRequests` is enabled. Carries the per-section
 * token breakdown of the rendered request for offline analysis —
 * driving the Phase 0 measurement that informs the curator's
 * shedding policy.
 *
 * Default-off; running profiling on every turn has measurable cost
 * (one tokenizer pass over the full system prompt + frames + tool
 * roster). Apps subscribe to `signals` (or `signalsFor(viewer)`),
 * collect, and post-process via [[sigil.diagnostics.RequestProfileReport]].
 */
case class WireRequestProfile(conversationId: Id[Conversation],
                              modelId: Id[Model],
                              participantId: ParticipantId,
                              profile: RequestProfile) extends Notice derives RW
