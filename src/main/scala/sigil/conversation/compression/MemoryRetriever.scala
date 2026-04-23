package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextMemory, ConversationView}
import sigil.participant.ParticipantId

/**
 * Per-turn memory retrieval pass. Given the current
 * [[ConversationView]] and participant chain, returns the ids of
 * [[ContextMemory]] records that should surface in this turn's
 * [[sigil.conversation.TurnInput.memories]] — rendered by the
 * provider into the system prompt's "Memories" section, so the LLM
 * sees them alongside the rolling context.
 *
 * The framework's [[StandardMemoryRetriever]] embeds a query derived
 * from the view (default: the latest non-agent message) and vector-
 * searches memories above a score threshold. This lets stored facts
 * surface even when nothing in the recent frames references them —
 * a user asking "What is my favorite color?" retrieves a fact
 * "User prefers blue" persisted weeks ago, with no lexical overlap
 * in the rolling window.
 *
 * Apps that want custom retrieval strategies (hybrid keyword + vector,
 * per-participant spaces, recency decay, etc.) implement this trait.
 * Apps that don't use memory retrieval use [[NoOpMemoryRetriever]] —
 * the curator won't set `memories` on its TurnInput output.
 */
trait MemoryRetriever {
  def retrieve(sigil: Sigil,
               view: ConversationView,
               chain: List[ParticipantId]): Task[Vector[Id[ContextMemory]]]
}
