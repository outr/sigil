package sigil.conversation.compression

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.{ContextFrame, Conversation}
import sigil.participant.ParticipantId

/**
 * Per-turn memory retrieval pass. Given the active conversation, the
 * frames that will render to the prompt this turn, and the participant
 * chain, returns a [[MemoryRetrievalResult]] with two vectors:
 *
 *   - query-relevant ids (→ [[sigil.conversation.TurnInput.memories]],
 *     rendered in the system prompt's "Memories" section)
 *   - always-on ids (→ [[sigil.conversation.TurnInput.criticalMemories]],
 *     rendered in the "Pinned directives" section)
 *
 * The framework's [[StandardMemoryRetriever]] runs a hybrid Lucene +
 * vector search for the query-relevant set and unconditionally includes
 * every pinned ([[sigil.conversation.ContextMemory.pinned]] = `true`)
 * memory in the chain's accessible spaces.
 *
 * Apps that want custom retrieval strategies (per-participant spaces,
 * recency decay, app-specific re-ranker) implement this trait. Apps
 * that don't use memory retrieval use [[NoOpMemoryRetriever]] — the
 * curator won't touch `memories` / `criticalMemories` on its
 * TurnInput output.
 */
trait MemoryRetriever {
  def retrieve(sigil: Sigil,
               conversationId: Id[Conversation],
               frames: Vector[ContextFrame],
               chain: List[ParticipantId]): Task[MemoryRetrievalResult]
}
