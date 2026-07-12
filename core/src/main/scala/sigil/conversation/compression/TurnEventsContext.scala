package sigil.conversation.compression

import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.Conversation
import sigil.participant.ParticipantId

/**
 * Typed handle passed to every [[CompactionInvariant]] so predicates
 * can reason about the slice's framing without each one looking up
 * conversation state independently.
 *
 *   - `conversationId` — the conversation the slice belongs to.
 *   - `claimedAt` — when the current agent iteration's claim was
 *      established. `None` when the caller has no claim in scope
 *      (curator per-turn shed, ad-hoc test fixtures); invariants that
 *      need the claim short-circuit to an empty set.
 *   - `agentId` — the participant that owns the claim. `None` for the
 *      same reasons.
 *
 * Kept narrow and typed; new fields are added explicitly when a new
 * invariant needs them rather than widening individual Event subclasses.
 */
case class TurnEventsContext(conversationId: Id[Conversation],
                             claimedAt: Option[Timestamp] = None,
                             agentId: Option[ParticipantId] = None,
                             /** Ids of tool invokes whose SETTLED result has
                               * already been rendered into a prompt the model
                               * consumed (tracked per-turn by the agent loop).
                               * [[CompactionInvariant.UndeliveredToolResults]]
                               * protects active-turn invokes NOT in this set —
                               * folding a result the agent never saw makes the
                               * agent re-execute its own completed work. Empty
                               * when the caller has no delivery tracking in
                               * scope (over-protects one turn; safe). */
                             deliveredToolResults: Set[Id[sigil.event.Event]] = Set.empty)
