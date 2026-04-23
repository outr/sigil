package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextSummary, Conversation}
import sigil.db.Model
import sigil.participant.ParticipantId

/**
 * Collapses a range of older frames into a single persisted
 * [[ContextSummary]]. LLM-driven — each implementation decides how to
 * prompt the summarization. When vector search is wired, the persisted
 * summary is auto-embedded (via [[Sigil.persistSummary]]) so the
 * `search_conversation` tool can retrieve it later.
 *
 * `None` return = "nothing was compressed, keep the frames as-is."
 * Callers use this signal to distinguish a transient failure (e.g.
 * provider error — `SummaryOnlyCompressor` swallows + logs) from a
 * deliberate no-op ([[NoOpContextCompressor]]).
 */
trait ContextCompressor {
  def compress(sigil: Sigil,
               modelId: Id[Model],
               chain: List[ParticipantId],
               frames: Vector[ContextFrame],
               conversationId: Id[Conversation]): Task[Option[ContextSummary]]
}
