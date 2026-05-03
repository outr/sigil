package sigil.tool.util

import fabric.io.JsonFormatter
import fabric.{Arr, Json, num, obj, str}
import rapid.{Stream, Task}
import sigil.{SpaceId, TurnContext}
import sigil.conversation.MemoryStatus
import sigil.event.{Event, Message, MessageRole}
import sigil.signal.EventState
import sigil.tool.model.{ResponseContent, SemanticSearchInput}
import sigil.tool.{ToolExample, ToolName, TypedTool}

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
 */
case object SemanticSearchTool extends TypedTool[SemanticSearchInput](
  name = ToolName("semantic_search"),
  description =
    """Search persisted memories. Returns matches ranked by embedding similarity when a vector
      |index is wired (otherwise Lucene/substring fallback). Use to recall a previously stored
      |fact before asking the user the same thing again.""".stripMargin,
  examples = List(
    ToolExample("Recall a preference", SemanticSearchInput(query = "user's preferred coding style")),
    ToolExample("Top 3 matches only", SemanticSearchInput(query = "deadline next week", limit = 3)),
    ToolExample("Include archived versions",
      SemanticSearchInput(query = "deploy target", includeHistory = true))
  ),
  keywords = Set("semantic", "search", "memory", "recall", "remember", "find", "vector", "similarity", "rag")
) {
  override protected def executeTyped(input: SemanticSearchInput, ctx: TurnContext): Stream[Event] = Stream.force {
    resolveSpaces(input, ctx).flatMap { spaces =>
      if (spaces.isEmpty)
        Task.pure(toMsg(ctx, render(input.query, Nil)))
      else
        ctx.sigil.searchMemories(input.query, spaces, input.limit).flatMap { hits =>
          val filtered = hits.filter { m =>
            m.status == MemoryStatus.Approved &&
              (input.includeHistory || m.validUntil.isEmpty)
          }
          Task.sequence(filtered.map(m => ctx.sigil.recordMemoryAccess(m._id)))
            .map(_ => toMsg(ctx, render(input.query, filtered)))
        }
    }.map(msg => Stream.emit[Event](msg))
  }

  private def resolveSpaces(input: SemanticSearchInput, ctx: TurnContext): Task[Set[SpaceId]] =
    if (input.spaces.nonEmpty) Task.pure(input.spaces)
    else ctx.sigil.defaultRecallSpaces(ctx.conversation.id)

  private def render(query: String, hits: List[sigil.conversation.ContextMemory]): String = {
    val items: Vector[Json] = hits.toVector.map { m =>
      val keyJson: Json = m.key match {
        case Some(k) => str(k)
        case None    => fabric.Null
      }
      obj(
        "memoryId"  -> str(m._id.value),
        "key"       -> keyJson,
        "label"     -> str(m.label),
        "summary"   -> str(m.summary),
        "fact"      -> str(m.fact),
        "pinned"    -> (if (m.pinned) fabric.bool(true) else fabric.bool(false)),
        "archived"  -> (if (m.validUntil.isDefined) fabric.bool(true) else fabric.bool(false))
      )
    }
    JsonFormatter.Compact(obj("query" -> str(query), "memories" -> Arr(items), "count" -> num(hits.size)))
  }

  private def toMsg(ctx: TurnContext, body: String): Message =
    Message(
      participantId  = ctx.caller,
      conversationId = ctx.conversation.id,
      topicId        = ctx.conversation.currentTopicId,
      content        = Vector(ResponseContent.Text(body)),
      state          = EventState.Complete,
      role           = MessageRole.Tool
    )
}
