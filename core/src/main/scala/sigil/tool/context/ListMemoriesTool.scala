package sigil.tool.context

import fabric.*
import fabric.io.JsonFormatter
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.conversation.ContextMemory
import sigil.event.{Event, Message, MessageRole}
import sigil.tokenize.HeuristicTokenizer
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.ResponseContent

/**
 * General memory-listing tool. Surfaces every memory the caller's
 * chain can access — pinned and unpinned — with optional filters for
 * space, pinned status, and substring query, paginated via
 * `offset` + `limit`.
 *
 * Output is a JSON object the agent reads on its next turn:
 * ```json
 * {
 *   "memories": [
 *     { "key", "label", "summary", "tokens", "spaceId", "pinned" }, ...
 *   ],
 *   "page": { "offset", "limit", "returned", "totalMatched", "hasMore" }
 * }
 * ```
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
case object ListMemoriesTool extends TypedTool[ListMemoriesInput](
  name = ToolName("list_memories"),
  description =
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
      |`pinned`. Use `lookup(capabilityType="Memory", name=key)` to pull a full fact;
      |use `pin_memory` / `unpin_memory` / `move_memory` / `forget_memory` to act.""".stripMargin,
  keywords = Set("list", "memories", "browse", "recall", "review", "all", "show")
) {
  override def resultTtl: Option[Int] = Some(0)
  override val requiresAccessibleSpaces: Boolean = true

  /** Server-side page-size clamp — defends against the agent passing
    * an enormous `limit` and dumping the entire memory store into
    * the next turn's prompt. */
  private val MaxPageSize: Int = 100

  override protected def executeTyped(input: ListMemoriesInput, context: TurnContext): Stream[Event] =
    Stream.force(collect(input, context).map { body =>
      Stream.emits(List[Event](Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(body)),
        role = MessageRole.Tool
      )))
    })

  private def collect(input: ListMemoriesInput, context: TurnContext): Task[String] =
    context.sigil.accessibleSpaces(context.chain).flatMap { accessible =>
      val effective = if (input.spaces.nonEmpty) input.spaces.intersect(accessible) else accessible
      if (effective.isEmpty)
        Task.pure("""{"memories":[],"page":{"offset":0,"limit":0,"returned":0,"totalMatched":0,"hasMore":false},"note":"No accessible memory spaces for this chain."}""")
      else
        context.sigil.findMemories(effective).map { memories =>
          val filtered = applyFilters(memories, input)
          val limit = math.max(1, math.min(input.limit, MaxPageSize))
          val offset = math.max(0, input.offset)
          val page = filtered.slice(offset, offset + limit)
          render(page, offset = offset, limit = limit, totalMatched = filtered.size)
        }
    }

  private def applyFilters(memories: List[ContextMemory], input: ListMemoriesInput): List[ContextMemory] = {
    val byPinned = input.pinned match {
      case Some(true)  => memories.filter(_.pinned)
      case Some(false) => memories.filterNot(_.pinned)
      case None        => memories
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

  private def render(page: List[ContextMemory], offset: Int, limit: Int, totalMatched: Int): String = {
    val tokenizer = HeuristicTokenizer
    val items = page.map { m =>
      val rendered = if (m.summary.trim.nonEmpty) m.summary else m.fact
      obj(
        "key" -> str(m.key.getOrElse(m._id.value)),
        "label" -> str(m.label),
        "summary" -> str(m.summary),
        "tokens" -> num(tokenizer.count(rendered)),
        "spaceId" -> str(m.spaceId.value),
        "pinned" -> bool(m.pinned)
      )
    }
    val pageInfo = obj(
      "offset"       -> num(offset),
      "limit"        -> num(limit),
      "returned"     -> num(page.size),
      "totalMatched" -> num(totalMatched),
      "hasMore"      -> bool(offset + page.size < totalMatched)
    )
    JsonFormatter.Default(obj(
      "memories" -> arr(items*),
      "page"     -> pageInfo
    ))
  }
}
