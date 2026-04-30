package sigil.tool.memory

import rapid.{Stream, Task}
import sigil.{SpaceId, TurnContext}
import sigil.conversation.ContextMemory
import sigil.event.{Event, Message, MessageRole}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Recall (search) memories matching a natural-language query. Wraps
 * [[sigil.Sigil.searchMemories]] which uses the embedding-backed
 * vector index when wired, or a space-scoped listing otherwise.
 *
 * Useful for "did I already note that?" or "what do I remember about
 * X?" agent flows. Pair with [[sigil.tool.util.SaveMemoryTool]]
 * (write) and [[ForgetMemoryTool]] (mark as superseded) to cover the
 * full memory CRUD surface.
 */
case object RecallMemoryTool extends TypedTool[RecallMemoryInput](
  name = ToolName("recall_memory"),
  description =
    """Search memories matching a natural-language query. Returns matching memory cards
      |(label, summary, fact, key) ranked by relevance when embeddings are configured,
      |otherwise ordered by recency. `spaces` optionally restricts the scope (omit to use
      |the caller's default space). Use to look up previously stored facts before deciding
      |whether to ask the user for the same information.""".stripMargin,
  examples = List(
    ToolExample("Recall facts about a project deadline",
      RecallMemoryInput(query = "project deadline", limit = 5))
  ),
  keywords = Set("memory", "recall", "search", "remember", "lookup")
) {
  override protected def executeTyped(input: RecallMemoryInput, context: TurnContext): Stream[Event] = {
    val msgTask: Task[Message] = resolveSpaces(input, context).flatMap { spaces =>
      if (spaces.isEmpty)
        Task.pure(toMsg(context, "[recall_memory] no memory space available."))
      else
        context.sigil.searchMemories(input.query, spaces, input.limit).map { hits =>
          toMsg(context, render(input.query, hits))
        }
    }
    Stream.force(msgTask.map(msg => Stream.emits[Event](List(msg))))
  }

  private def resolveSpaces(input: RecallMemoryInput, context: TurnContext): Task[Set[SpaceId]] =
    if (input.spaces.nonEmpty) Task.pure(input.spaces)
    else context.sigil.defaultMemorySpace(context.conversation.id).map(_.toSet)

  private def render(query: String, hits: List[ContextMemory]): String =
    if (hits.isEmpty) s"[recall_memory] no matches for \"$query\""
    else {
      val sb = new StringBuilder(s"[recall_memory] ${hits.size} match(es) for \"$query\":\n")
      hits.foreach { m =>
        val keyStr = if (m.key.nonEmpty) s" key=${m.key}" else ""
        val labelStr = if (m.label.nonEmpty) s" — ${m.label}" else ""
        sb.append(s"  • ${m._id.value}$keyStr$labelStr\n")
        sb.append(s"      ${m.fact}\n")
        if (m.summary.nonEmpty) sb.append(s"      ${m.summary}\n")
      }
      sb.toString
    }

  private def toMsg(context: TurnContext, body: String): Message =
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(body)),
      state = EventState.Complete,
      role = MessageRole.Tool
    )
}
