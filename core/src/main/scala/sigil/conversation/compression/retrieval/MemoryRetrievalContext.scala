package sigil.conversation.compression.retrieval

import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.{Sigil, SpaceId}
import sigil.conversation.{ContextMemory, Conversation}
import sigil.provider.Mode

/**
 * Per-turn immutable inputs shared by every retrieval-pipeline stage:
 * the host, the composed retrieval query, the resolved space scope,
 * the conversation's current mode (for the affinity gate), the turn
 * timestamp, the count cap, the recall candidate-pool size, and the
 * ids to exclude from the surfaced set (the turn's pinned memories —
 * they render in the Pinned section already and must not
 * double-render).
 */
case class MemoryRetrievalContext(sigil: Sigil,
                                  conversationId: Id[Conversation],
                                  query: String,
                                  spaces: Set[SpaceId],
                                  currentMode: Option[Id[Mode]],
                                  now: Timestamp,
                                  limit: Int,
                                  candidatePool: Int,
                                  exclude: Set[Id[ContextMemory]] = Set.empty)
