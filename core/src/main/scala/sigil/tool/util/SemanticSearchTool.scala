package sigil.tool.util

import rapid.Task
import sigil.{SpaceId, TurnContext}
import sigil.conversation.MemoryStatus
import sigil.tool.model.{SemanticSearchHit, SemanticSearchInput, SemanticSearchOutput}
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}

/**
 * The unified memory-retrieval tool. Wraps
 * [[sigil.Sigil.searchMemories]]; embedding-ranked when a vector
 * index is wired, Lucene/substring fallback otherwise.
 *
 * Filters to `MemoryStatus.Approved` and current versions by default;
 * pass `includeHistory = true` to surface superseded records too.
 *
 * Records access on every returned record so retention / freshness
 * downstream can prefer recently-touched memories.
 *
 * Falls back to [[sigil.Sigil.defaultRecallSpaces]] when the agent
 * doesn't pass an explicit `spaces` set.
 *
 * Emits a typed [[SemanticSearchOutput]] (`query`, `memories: List[SemanticSearchHit]`, `count`).
 */
case object SemanticSearchTool extends TypedOutputTool[SemanticSearchInput, SemanticSearchOutput](
  name = ToolName("semantic_search"),
  description =
    """Search persisted memories. Returns matches ranked by embedding similarity when a vector
      |index is wired (otherwise Lucene/substring fallback). Use to recall a previously stored
      |fact before asking the user the same thing again. Returns
      |`{query, memories: [{memoryId, key?, label, summary, fact, pinned, archived, confidence, justification?}], count}`.""".stripMargin,
  examples = List(
    ToolExample("Recall a preference", SemanticSearchInput(query = "user's preferred coding style")),
    ToolExample("Top 3 matches only", SemanticSearchInput(query = "deadline next week", limit = 3)),
    ToolExample("Include archived versions",
      SemanticSearchInput(query = "deploy target", includeHistory = true))
  ),
  keywords = Set("semantic", "search", "memory", "recall", "remember", "find", "vector", "similarity", "rag")
) with sigil.tool.ReadOnlyInternalTool {
  override protected def executeTyped(input: SemanticSearchInput, ctx: TurnContext): Task[SemanticSearchOutput] =
    resolveSpaces(input, ctx).flatMap { spaces =>
      if (spaces.isEmpty)
        Task.pure(SemanticSearchOutput(query = input.query, memories = Nil, count = 0))
      else
        ctx.sigil.searchMemories(input.query, spaces, input.limit).flatMap { hits =>
          val filtered = hits.filter { m =>
            m.status == MemoryStatus.Approved &&
              (input.includeHistory || m.validUntil.isEmpty)
          }
          Task.sequence(filtered.map(m => ctx.sigil.recordMemoryAccess(m._id)))
            .map(_ => SemanticSearchOutput(
              query    = input.query,
              memories = filtered.map(toHit),
              count    = filtered.size
            ))
        }
    }

  private def resolveSpaces(input: SemanticSearchInput, ctx: TurnContext): Task[Set[SpaceId]] =
    if (input.spaces.nonEmpty) Task.pure(input.spaces)
    else ctx.sigil.defaultRecallSpaces(ctx.conversation.id)

  private def toHit(m: sigil.conversation.ContextMemory): SemanticSearchHit =
    SemanticSearchHit(
      memoryId      = m._id.value,
      key           = m.key,
      label         = m.label,
      summary       = m.summary,
      fact          = m.fact,
      pinned        = m.pinned,
      archived      = m.validUntil.isDefined,
      confidence    = m.confidence,
      justification = m.justification
    )
}
