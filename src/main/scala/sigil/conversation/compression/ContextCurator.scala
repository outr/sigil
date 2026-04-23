package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.conversation.{ConversationView, TurnInput}
import sigil.db.Model
import sigil.participant.ParticipantId

/**
 * Per-turn context curator — decides what the provider should see for
 * this turn. Implementations:
 *
 *   1. Run the [[ContextOptimizer]] to clean up redundant frames.
 *   2. Build a tentative [[TurnInput]] from the view.
 *   3. Estimate tokens; if over [[ContextBudget]], invoke a
 *      [[ContextCompressor]] on the older half of frames, append the
 *      new summary id to `TurnInput.summaries`, and swap the trimmed
 *      frames into a view copy (the persistent view is untouched).
 *
 * Hook into [[sigil.Sigil.curate]] by returning a wired
 * [[StandardContextCurator]] (or a custom impl).
 */
trait ContextCurator {
  def curate(view: ConversationView,
             modelId: Id[Model],
             chain: List[ParticipantId]): Task[TurnInput]
}
