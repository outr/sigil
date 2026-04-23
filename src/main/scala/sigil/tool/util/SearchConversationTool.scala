package sigil.tool.util

import rapid.Stream
import sigil.TurnContext
import sigil.event.{Event, Message, TopicChange}
import sigil.tool.model.{ResponseContent, SearchConversationInput}
import sigil.tool.{Tool, ToolExample}

/**
 * Opt-in util-tier tool: retrieves historical events from the
 * persistent event log of the caller's current conversation. The
 * agent receives the rendered results as a Message it can read on its
 * next turn.
 *
 * When an [[sigil.embedding.EmbeddingProvider]] + [[sigil.vector.VectorIndex]]
 * are wired on the [[sigil.Sigil]] instance, the query is
 * embedded and hit against the vector index with a
 * `kind=message, conversationId=…` filter (semantic search). Otherwise
 * [[sigil.Sigil.searchConversationEvents]] falls back to an in-memory
 * substring scan over events.
 *
 * Companion to conversation compression — compression trims the
 * rolling context to stay under a token budget, but the event log
 * retains everything. This tool is how the agent recovers detail that
 * compression dropped from its immediate view.
 *
 * Not in [[sigil.tool.core.CoreTools]]; apps add it explicitly to an
 * agent's roster or expose it via capability discovery.
 */
object SearchConversationTool extends Tool[SearchConversationInput] {
  override protected def uniqueName: String = "search_conversation"

  override protected def description: String =
    """Search the persistent log of the current conversation for earlier events that match a query.
      |
      |Use this when:
      |  - you need a detail from earlier in the conversation that is no longer visible in the rolling context
      |  - you're resuming an older topic and want to pull its prior exchanges back into view
      |  - you want to confirm something you think was said rather than guess
      |
      |Do NOT use for very recent history — if the answer is in the last few messages, re-read the context
      |instead of searching. Specific keywords / noun phrases beat generic phrases like "what did we say".
      |
      |`query` — the search text. For semantic search backends (Qdrant), a short phrase works; for the
      |substring fallback, include distinctive words from the content you want to find.
      |
      |`topicId` — optional; restrict to events tagged with a specific topic.
      |
      |`limit` — max results (default 10).""".stripMargin

  override protected def examples: List[ToolExample[SearchConversationInput]] = List(
    ToolExample(
      "Find earlier exchanges mentioning the Qdrant deployment",
      SearchConversationInput(query = "Qdrant deployment")
    )
  )

  override def execute(input: SearchConversationInput, context: TurnContext): Stream[Event] =
    Stream.force {
      context.sigil
        .searchConversationEvents(
          conversationId = context.conversation.id,
          query = input.query,
          topicId = input.topicId,
          limit = input.limit
        )
        .map { hits =>
          val body = if (hits.isEmpty) s"No results for query: ${input.query}"
                     else renderHits(hits)
          Stream.emits(List[Event](
            Message(
              participantId = context.caller,
              conversationId = context.conversation.id,
              topicId = context.conversation.currentTopicId,
              content = Vector(ResponseContent.Text(body))
            )
          ))
        }
    }

  private def renderHits(events: List[Event]): String = {
    val sb = new StringBuilder
    sb.append(s"${events.size} result(s):\n")
    events.foreach { e =>
      val snippet = e match {
        case m: Message =>
          m.content.collect { case ResponseContent.Text(t) => t }.mkString(" ").take(280)
        case tc: TopicChange => s"[topic change] ${tc.newLabel}"
        case other           => other.getClass.getSimpleName
      }
      sb.append(s"- ${e.timestamp.value} ${e.participantId.value} (topic=${e.topicId.value}): $snippet\n")
    }
    sb.toString
  }
}
