package sigil.tool.context

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.conversation.ContextMemory
import sigil.tokenize.HeuristicTokenizer
import sigil.tool.model.{ListMemoriesOutput, MemoryListEntry, MemoryListPage}
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolGates, ToolIO, ToolName, ToolProfile, ToolSpec}

/**
 * General memory-listing tool. Surfaces every memory the caller's
 * chain can access — pinned and unpinned — with optional filters for
 * space, pinned status, and substring query, paginated via
 * `offset` + `limit`.
 *
 * Emits a typed [[ListMemoriesOutput]] — `Listed` with a page of
 * matching memories plus its pagination envelope, or
 * `NoAccessibleSpaces` when the caller's chain has no accessible
 * memory space.
 *
 * Use cases:
 *   - "What do you remember about me?" — the agent calls
 *     `list_memories(query = "user", limit = 25)` and renders the
 *     summaries.
 *   - "Show me your project notes" — `list_memories(spaces =
 *     Set(ProjectSpace))`.
 *   - Pagination — agent calls again with `offset = previous + limit`
 *     when the user wants more.
 *
 * Pair with `lookup(capabilityType="Memory", name=key)` to fetch the
 * full fact text when the summary alone isn't enough; pair with
 * `pin_memory` / `unpin_memory` / `move_memory` / `forget_memory` to
 * act on a selection.
 */
case object ListMemoriesTool extends Tool {
  type Input = ListMemoriesInput
  type Output = ListMemoriesOutput
  val io: ToolIO[ListMemoriesInput, ListMemoriesOutput] = ToolIO.derived[ListMemoriesInput, ListMemoriesOutput]
  override val name = ToolName("list_memories")
  override val description =
    """List memories you can see — pinned and unpinned — with filters and pagination.
      |
      |- `spaces` — optional filter; empty = every space your chain can access.
      |- `query`  — optional case-insensitive substring matched against key / label /
      |             summary / fact / tags.
      |- `pinned` — optional filter: omit for both, `true` for pinned only, `false` for
      |             unpinned only.
      |- `offset` — 0-based page offset (default 0).
      |- `limit`  — page size (default 25, max 100).
      |
      |Returns each memory's `key`, `label`, `summary`, token cost, `spaceId`, and
      |`pinned`. Use the lookup tool to pull a memory's full fact; use the
      |memory-pinning / unpinning / moving / forgetting tools to act on individual
      |entries.""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(
      effect = Effect.ReadOnly(Freshness.Volatile),
      gates = ToolGates(requiresAccessibleSpaces = true)
    ),
    discovery = DiscoverySpec(keywords = Set("list", "memories", "browse", "recall", "review", "all", "show"))
  )

  /**
   * Server-side page-size clamp — defends against the agent passing
   * an enormous `limit` and dumping the entire memory store into
   * the next turn's prompt.
   */
  private val MaxPageSize: Int = 100

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: ListMemoriesInput, context: ToolContext): Task[ListMemoriesOutput] =
    context.sigil.accessibleSpaces(context.chain, context.conversation.id).flatMap { accessible =>
      val effective = if (input.spaces.nonEmpty) input.spaces.intersect(accessible) else accessible
      if (effective.isEmpty)
        Task.pure(ListMemoriesOutput.NoAccessibleSpaces("No accessible memory spaces for this chain."))
      else {
        // pinned-only requests use the Lucene-pushed critical-memory
        // query (O(N_pinned)) instead of pulling every memory and
        // filtering — the typical "what's pinned in my context?"
        // path needs to stay fast as the memory store grows.
        val source: Task[List[ContextMemory]] = input.pinned match {
          case Some(true) => context.sigil.findCriticalMemories(effective)
          case _ => context.sigil.findMemories(effective)
        }
        source.map { memories =>
          val filtered = applyFilters(memories, input)
          val limit = math.max(1, math.min(input.limit, MaxPageSize))
          val offset = math.max(0, input.offset)
          val page = filtered.slice(offset, offset + limit)
          ListMemoriesOutput.Listed(
            memories = page.map(toEntry),
            page = MemoryListPage(
              offset = offset,
              limit = limit,
              returned = page.size,
              totalMatched = filtered.size,
              hasMore = offset + page.size < filtered.size
            )
          )
        }
      }
    }

  private def applyFilters(memories: List[ContextMemory], input: ListMemoriesInput): List[ContextMemory] = {
    val byPinned = input.pinned match {
      case Some(true) => memories.filter(_.pinned)
      case Some(false) => memories.filterNot(_.pinned)
      case None => memories
    }
    val byQuery = input.query.map(_.trim).filter(_.nonEmpty) match {
      case None => byPinned
      case Some(q) =>
        val needle = q.toLowerCase
        byPinned.filter { m =>
          m.key.exists(_.toLowerCase.contains(needle)) ||
          m.label.toLowerCase.contains(needle) ||
          m.summary.toLowerCase.contains(needle) ||
          m.fact.toLowerCase.contains(needle) ||
          m.keywords.exists(_.toLowerCase.contains(needle))
        }
    }
    // Stable ordering for deterministic pagination: pinned first, then
    // by lastAccessedAt descending, then by _id for tie-break.
    byQuery.sortBy(m => (!m.pinned, -m.lastAccessedAt.value, m._id.value))
  }

  private def toEntry(m: ContextMemory): MemoryListEntry = MemoryListEntry.from(m)
}
