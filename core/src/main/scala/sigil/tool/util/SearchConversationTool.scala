package sigil.tool.util

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.event.{Event, Message, TopicChange}
import sigil.tool.model.{ResponseContent, SearchConversationHit, SearchConversationInput, SearchConversationOutput}
import sigil.tool.{Tool, ToolExample, ToolName}

/**
 * Opt-in util-tier tool: retrieves historical events from the persistent
 * event log of the caller's current conversation. Companion to
 * conversation compression — compression trims the rolling context;
 * the event log retains everything; this tool recovers detail.
 *
 * Emits a typed [[SearchConversationOutput]] (`query`, `hits: List[SearchConversationHit]`, `count`).
 */
case object SearchConversationTool extends Tool with sigil.tool.ReadOnlyInternalTool {
  type Input  = SearchConversationInput
  type Output = SearchConversationOutput
  val inputRW  = summon[RW[SearchConversationInput]]
  val outputRW = summon[RW[SearchConversationOutput]]
  val name = ToolName("search_conversation")
  val description =
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
      |`limit` — max results (default 10).
      |
      |Returns `{query, hits: [{eventId, timestamp, participantId, topicId, eventType, snippet}], count}`.""".stripMargin
  override val examples = List(
    ToolExample(
      "Find earlier exchanges mentioning the Qdrant deployment",
      SearchConversationInput(query = "Qdrant deployment")
    )
  )
  override val keywords = Set("search", "conversation", "history", "find", "recall")

  override def executeOutput(input: SearchConversationInput, context: ToolContext): Task[SearchConversationOutput] =
    context.sigil
      .searchConversationEvents(
        conversationId = context.conversation.id,
        query = input.query,
        topicId = input.topicId,
        limit = input.limit
      )
      .map { events =>
        SearchConversationOutput(
          query = input.query,
          hits  = events.map(toHit),
          count = events.size
        )
      }

  private def toHit(e: Event): SearchConversationHit = {
    val snippet = e match {
      case m: Message =>
        m.content.collect { case ResponseContent.Text(t) => t }.mkString(" ").take(280)
      case tc: TopicChange => s"[topic change] ${tc.newLabel}"
      case other           => other.getClass.getSimpleName
    }
    SearchConversationHit(
      eventId       = e._id.value,
      timestamp     = e.timestamp.value,
      participantId = e.participantId.value,
      topicId       = e.topicId.value,
      eventType     = e.getClass.getSimpleName,
      snippet       = snippet
    )
  }
}
