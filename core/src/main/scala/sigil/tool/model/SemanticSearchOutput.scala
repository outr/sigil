package sigil.tool.model

import fabric.rw.*

/**
 * Per-hit shape inside [[SemanticSearchOutput]]. Mirrors
 * [[sigil.conversation.ContextMemory]]'s agent-facing surface
 * without leaking framework-internal fields (status, spaceId,
 * embeddings). `pinned`, `archived`, `confidence`, `justification`
 * are populated when set; `key` and `justification` ride as
 * `Option`.
 */
case class SemanticSearchHit(memoryId: String,
                             key: Option[String],
                             label: String,
                             summary: String,
                             fact: String,
                             pinned: Boolean,
                             archived: Boolean,
                             confidence: Double,
                             justification: Option[String])
  derives RW

/**
 * Typed result for [[sigil.tool.util.SemanticSearchTool]]. Echoes
 * the `query` so consuming agents can correlate results with their
 * own prior calls. `count` is the post-filter size — agents
 * detecting truncation compare against the input's `limit`.
 */
case class SemanticSearchOutput(query: String,
                                memories: List[SemanticSearchHit],
                                count: Int)
  derives RW
