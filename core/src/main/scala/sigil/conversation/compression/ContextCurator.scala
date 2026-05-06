package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.participant.ParticipantId

/**
 * Per-turn context curator — decides what the provider should see for
 * this turn. Implementations:
 *
 *   1. Pull frames for the conversation via [[sigil.Sigil.framesFor]].
 *   2. Run the [[ContextOptimizer]] to clean up redundant frames.
 *   3. Build a tentative [[TurnInput]] from frames + chain
 *      participant projections.
 *   4. Estimate tokens; if over [[ContextBudget]], invoke a
 *      [[ContextCompressor]] on the older frames, append the new
 *      summary id to `TurnInput.summaries`, and replace the trimmed
 *      frames inline.
 *
 * Bug #26 — the curator no longer takes a `ConversationView`; it
 * builds its own per-turn snapshot from `db.events` (frames via
 * [[sigil.event.Event.contextFrame]]) and `db.participantProjections`.
 *
 * Hook into [[sigil.Sigil.curate]] by returning a wired
 * [[StandardContextCurator]] (or a custom impl).
 */
trait ContextCurator {
  def curate(conversationId: Id[Conversation],
             modelId: Id[Model],
             chain: List[ParticipantId]): Task[TurnInput]
}
