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

  /**
   * Re-fit an already-built [[TurnInput]] to a (typically smaller)
   * model's context window. Same flow as hitting the context limit on
   * the model the turn was curated for — it just sizes down to a
   * different window. Used when per-turn routing lands the turn on a
   * model whose window is smaller than the one the context was curated
   * against (complexity reclassification, tier degrade, a credential
   * that doesn't grant the catalog window): re-running the budget gate
   * against the served model's window keeps the prompt inside it
   * instead of relying on the provider's crude emergency shed.
   *
   * The reduction reuses the curator's existing non-lossy-first
   * cascade (caption-preserving image eviction, dropping only
   * recoverable retrieved memories / information / summaries, eliding
   * oversized frames to `reload_content` pointers) before any lossy
   * summarization — and even that stays recoverable from the durable
   * event log. Default: identity (no curator implementation to re-run).
   */
  def refit(turnInput: TurnInput,
            modelId: Id[Model],
            chain: List[ParticipantId]): Task[TurnInput] =
    Task.pure(turnInput)
}
