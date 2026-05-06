package sigil.conversation.compression

import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextSummary, Conversation}
import sigil.db.Model
import sigil.participant.ParticipantId

/**
 * Collapses a stream of older frames into a single persisted
 * [[ContextSummary]]. LLM-driven — each implementation decides how to
 * prompt the summarization. When vector search is wired, the persisted
 * summary is auto-embedded (via [[Sigil.persistSummary]]) so the
 * `search_conversation` tool can retrieve it later.
 *
 * Bug #26 — frames arrive as a `rapid.Stream[ContextFrame]` so
 * implementations consuming long histories can chunk + merge without
 * materializing the full older tail in memory.
 *
 * `None` return = "nothing was compressed, keep the frames as-is."
 * Callers use this signal to distinguish a transient failure (e.g.
 * provider error — `SummaryOnlyCompressor` swallows + logs) from a
 * deliberate no-op ([[NoOpContextCompressor]]).
 *
 * `callerModelId` is the calling agent's modelId — implementations
 * should NOT inherit it as their own summarization model (bug #24).
 * Resolve a SummarizationWork-tier model via the framework's strategy
 * and fall back to `callerModelId` only when no SummarizationWork
 * candidate is available.
 */
trait ContextCompressor {
  def compress(sigil: Sigil,
               callerModelId: Id[Model],
               chain: List[ParticipantId],
               frames: Stream[ContextFrame],
               conversationId: Id[Conversation]): Task[Option[ContextSummary]]
}
