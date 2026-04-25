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
 *     rendered in the "Critical directives" section)
 *
 * The framework's [[StandardMemoryRetriever]] embeds a query derived
 * from the view (default: the latest non-agent message) and vector-
 * searches memories for the relevant set, while unconditionally
 * including every [[sigil.conversation.MemorySource.Critical]] record
 * in the configured spaces. This lets stored facts surface even when
 * nothing in the recent frames references them — a user asking
 * "What is my favorite color?" retrieves a fact "User prefers blue"
 * persisted weeks ago, with no lexical overlap in the rolling window —
 * while critical directives (safety rules, identity anchors) always
 * render regardless of what the user is discussing.
 *
 * Apps that want custom retrieval strategies (hybrid keyword + vector,
 * per-participant spaces, recency decay, etc.) implement this trait.
 * Apps that don't use memory retrieval use [[NoOpMemoryRetriever]] —
 * the curator won't touch `memories` / `criticalMemories` on its
 * TurnInput output.
 */
trait MemoryRetriever {
  def retrieve(sigil: Sigil,
               view: ConversationView,
               chain: List[ParticipantId]): Task[MemoryRetrievalResult]
}
