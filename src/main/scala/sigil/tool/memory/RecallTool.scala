package sigil.tool.memory

import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.conversation.{ContextMemory, MemoryStatus}
import sigil.event.{Event, Message}
import sigil.tool.model.ResponseContent
import sigil.tool.{Tool, ToolExample}

/**
 * Opt-in tool: agent-driven retrieval of persisted memories. Hits
 * [[sigil.Sigil.searchMemories]] (vector-semantic when wired, listing
 * fallback otherwise), post-filters to approved + current versions
 * by default, and renders the results as a [[Message]] the agent
 * will read on its next turn.
 */
object RecallTool extends Tool[RecallInput] {
  override protected def uniqueName: String = "recall"

  override protected def description: String =
    """Retrieve durable facts previously stored via `remember` (or auto-extracted from conversation).
      |
      |`query`          — what you're looking for. For semantic search, a short phrase works;
      |                   for the substring fallback, include distinctive words.
      |`limit`          — max results (default 10).
      |`includeHistory` — include superseded (archived) versions too (default false).
      |`spaces`         — optional scopes. Omit to use the caller's default.""".stripMargin

  override protected def examples: List[ToolExample[RecallInput]] = List(
    ToolExample(
      "What did the user say about their preferred UI theme?",
      RecallInput(query = "user UI theme preference")
    )
  )

  override def execute(input: RecallInput, context: TurnContext): Stream[Event] =
    Stream.force {
      resolveSpaces(input, context).flatMap { spaces =>
        if (spaces.isEmpty)
          Task.pure(errorMessage(context, "recall: no memory spaces available."))
        else
          context.sigil.searchMemories(input.query, spaces, input.limit).flatMap { hits =>
            val filtered = hits.filter { m =>
              m.status == MemoryStatus.Approved &&
                (input.includeHistory || m.validUntil.isEmpty)
            }
            // Record access for LRU-style retention policy.
            Task.sequence(filtered.map(m => context.sigil.recordMemoryAccess(m._id)))
              .map(_ => renderHits(context, input.query, filtered))
          }
      }.map(msg => Stream.emits(List[Event](msg)))
    }

  private def resolveSpaces(input: RecallInput, context: TurnContext) =
    if (input.spaces.nonEmpty) Task.pure(input.spaces)
    else context.sigil.defaultRecallSpaces(context.conversation.id)

  private def renderHits(context: TurnContext, query: String, hits: List[ContextMemory]): Message = {
    val body =
      if (hits.isEmpty) s"[recall] no matches for: $query"
      else {
        val sb = new StringBuilder(s"[recall] ${hits.size} result(s) for: $query\n")
        hits.foreach { m =>
          val superseded = m.validUntil.isDefined
          val prefix = if (superseded) "  (archived) " else "  "
          val head = if (m.label.nonEmpty) s"${m.key} — ${m.label}" else m.key
          sb.append(s"$prefix$head\n")
          val content = if (m.summary.nonEmpty) m.summary else m.fact
          sb.append(s"    $content\n")
        }
        sb.toString
      }
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(body))
    )
  }

  private def errorMessage(context: TurnContext, body: String): Message =
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(body))
    )
}
