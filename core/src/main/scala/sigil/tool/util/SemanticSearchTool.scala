package sigil.tool.util

import fabric.rw.*
import rapid.Task
import sigil.SpaceId
import sigil.tool.ToolContext
import sigil.conversation.MemoryStatus
import sigil.tool.model.{SemanticSearchHit, SemanticSearchInput, SemanticSearchOutput}
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolExample, ToolIO, ToolName, ToolProfile, ToolSpec}

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
case object SemanticSearchTool extends Tool {
  type Input  = SemanticSearchInput
  type Output = SemanticSearchOutput
  val io: ToolIO[SemanticSearchInput, SemanticSearchOutput] = ToolIO.derived[SemanticSearchInput, SemanticSearchOutput].withExamples(
    ToolExample("Recall a preference", SemanticSearchInput(query = "user's preferred coding style")),
    ToolExample("Top 3 matches only", SemanticSearchInput(query = "deadline next week", limit = 3)),
    ToolExample("Include archived versions",
      SemanticSearchInput(query = "deploy target", includeHistory = true))
  )
  override val name = ToolName("semantic_search")
  override val description =
    """Search persisted memories. Returns matches ranked by embedding similarity when a vector
      |index is wired (otherwise Lucene/substring fallback). Use to recall a previously stored
      |fact before asking the user the same thing again. Returns
      |`{query, memories: [{memoryId, key?, label, summary, fact, pinned, archived, confidence, justification?}], count}`.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("semantic", "search", "memory", "recall", "remember", "find", "vector", "similarity", "rag"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: SemanticSearchInput, ctx: ToolContext): Task[SemanticSearchOutput] =
    resolveSpaces(input, ctx).flatMap { spaces =>
      if (spaces.isEmpty)
        Task.pure(SemanticSearchOutput(query = input.query, memories = Nil, count = 0))
      else
        ctx.sigil.searchMemories(input.query, spaces, input.limit).flatMap { hits =>
          val filtered = hits.filter { m =>
            m.status == MemoryStatus.Approved &&
              (input.includeHistory || m.validUntil.isEmpty)
          }
          ctx.sigil.recordMemoryAccesses(filtered.map(_._id))
            .map(_ => SemanticSearchOutput(
              query    = input.query,
              memories = filtered.map(toHit),
              count    = filtered.size
            ))
        }
    }

  private def resolveSpaces(input: SemanticSearchInput, ctx: ToolContext): Task[Set[SpaceId]] =
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
