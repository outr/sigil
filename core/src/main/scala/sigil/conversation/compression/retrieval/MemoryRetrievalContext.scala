package sigil.conversation.compression.retrieval

import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.{Sigil, SpaceId}
import sigil.conversation.{ContextMemory, Conversation}
import sigil.provider.Mode

/**
 * Per-turn immutable inputs shared by every retrieval-pipeline stage:
 * the host, the question text and context terms driving recall, the
 * resolved space scope, the conversation's current mode (for the
 * affinity gate), the turn timestamp, the count cap, the recall
 * candidate-pool size, and the ids to exclude from the surfaced set
 * (the turn's pinned memories — they render in the Pinned section
 * already and must not double-render).
 *
 * `query` is the user's actual question (the last non-agent message
 * by default) — UNDILUTED. It drives the vector and lexical legs, so
 * a specific factual question matches the memory that answers it
 * instead of the conversation's general theme. `contextTerms` carries
 * the conversational context — the classifier's `currentKeywords`
 * plus the topic label's terms — as a SEPARATE keyword leg with its
 * own fusion weight, so context informs the ranking without being
 * mixed into the question's embedding or BM25 tokens.
 */
case class MemoryRetrievalContext(sigil: Sigil,
                                  conversationId: Id[Conversation],
                                  query: String,
                                  spaces: Set[SpaceId],
                                  currentMode: Option[Id[Mode]],
                                  now: Timestamp,
                                  limit: Int,
                                  candidatePool: Int,
                                  exclude: Set[Id[ContextMemory]] = Set.empty,
                                  contextTerms: List[String] = Nil)
