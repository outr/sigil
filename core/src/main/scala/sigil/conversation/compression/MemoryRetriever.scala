package sigil.conversation.compression

import rapid.Task
import sigil.Sigil
import sigil.conversation.ConversationView
import sigil.participant.ParticipantId

/**
 * Per-turn memory retrieval pass. Given the current
 * [[ConversationView]] and participant chain, returns a
 * [[MemoryRetrievalResult]] with two vectors:
 *
 *   - query-relevant ids (→ [[sigil.conversation.TurnInput.memories]],
 *     rendered in the system prompt's "Memories" section)
 *   - always-on ids (→ [[sigil.conversation.TurnInput.criticalMemories]],
 *     rendered in the "Pinned directives" section)
 *
 * The framework's [[StandardMemoryRetriever]] runs a hybrid Lucene +
 * vector search for the query-relevant set and unconditionally
 * includes every pinned ([[sigil.conversation.ContextMemory.pinned]]
 * = `true`) memory in the chain's accessible spaces. This lets stored
 * facts surface even when nothing in the recent frames references
 * them — a user asking "What is my favorite color?" retrieves a fact
 * "User prefers blue" persisted weeks ago, with no lexical overlap in
 * the rolling window — while pinned directives ("always reply in
 * markdown", "never delete files without confirmation") render
 * regardless of what the user is discussing.
 *
 * Apps that want custom retrieval strategies (per-participant spaces,
 * recency decay, app-specific re-ranker) implement this trait. Apps
 * that don't use memory retrieval use [[NoOpMemoryRetriever]] — the
 * curator won't touch `memories` / `criticalMemories` on its
 * TurnInput output.
 */
trait MemoryRetriever {
  def retrieve(sigil: Sigil,
               view: ConversationView,
               chain: List[ParticipantId]): Task[MemoryRetrievalResult]
}
