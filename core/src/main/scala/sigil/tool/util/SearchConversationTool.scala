package sigil.tool.util

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.event.{Event, Message, TopicChange}
import sigil.tool.model.{ResponseContent, SearchConversationHit, SearchConversationInput, SearchConversationOutput}
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolExample, ToolIO, ToolName, ToolProfile, ToolResult, ToolSpec}

/**
 * Opt-in util-tier tool: retrieves historical events from the persistent
 * event log of either the caller's current conversation OR a related
 * conversation (parent or worker, per sigil #289). Companion to
 * conversation compression — compression trims the rolling context;
 * the event log retains everything; this tool recovers detail.
 *
 * Two modes:
 *   - **Search** (non-empty query): returns ranked top-`limit` hits.
 *   - **Walk** (empty / blank query): returns the next page of events
 *     in chronological order. Useful for forensics on a worker's
 *     transcript when the agent doesn't know what to search for yet.
 *
 * Emits a typed [[SearchConversationOutput]].
 */
case object SearchConversationTool extends Tool {
  type Input = SearchConversationInput
  type Output = SearchConversationOutput
  val io: ToolIO[SearchConversationInput, SearchConversationOutput] =
    ToolIO.derived[SearchConversationInput, SearchConversationOutput].withExamples(
      ToolExample(
        "Find earlier exchanges mentioning the Qdrant deployment",
        SearchConversationInput(query = "Qdrant deployment")
      ),
      ToolExample(
        "Walk the most recent events of a worker conversation",
        SearchConversationInput(query = "", conversationId = Some(sigil.conversation.Conversation.id("worker-xyz")))
      )
    )
  override val name = ToolName("search_conversation")
  override val description =
    """Search OR walk the persistent log of a conversation (the caller's own, its parent, or one of
      |its workers) for earlier events.
      |
      |Two modes:
      |  - **Search** — pass a non-empty `query`. Returns ranked top-`limit` hits. Use when you know
      |    distinctive keywords / noun phrases.
      |  - **Walk** — pass an empty or blank `query`. Returns the next page of events in chronological
      |    order. Use for forensics ("what happened next?") or when you don't know what to search for.
      |
      |Use this when:
      |  - you need a detail from earlier in the conversation that is no longer visible in the rolling context
      |  - you're resuming an older topic and want to pull its prior exchanges back into view
      |  - you want to confirm something you think was said rather than guess
      |  - (sigil #289) you delegated to a worker and want to read the worker's transcript to answer a
      |    user's clarification question or investigate a decision the worker made
      |
      |Do NOT use for very recent history — if the answer is in the last few messages, re-read the context
      |instead of searching. Specific keywords / noun phrases beat generic phrases like "what did we say".
      |
      |`query` — the search text. Empty / blank switches to walk mode.
      |
      |`topicId` — optional; restrict to events tagged with a specific topic.
      |
      |`limit` — max results / page size (default 10).
      |
      |`conversationId` — optional; target a specific conversation. Defaults to the caller's current.
      |Allowed targets: own conversation, parent conversation, or any of the caller's workers'.
      |
      |`page` — zero-indexed page for walk mode (ignored in search mode).
      |
      |Returns `{query, hits: [{eventId, timestamp, participantId, topicId, eventType, snippet}], count}`.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("search", "conversation", "history", "find", "recall", "walk", "browse", "forensics"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: SearchConversationInput,
                            context: ToolContext): Task[ToolResult[SearchConversationOutput]] = {
    val targetConvId = input.conversationId.getOrElse(context.conversation.id)
    val currentConvId = context.conversation.id
    context.sigil.canReadConversation(currentConvId, targetConvId).flatMap {
      case Left(reason) =>
        Task.pure(ToolResult.failure(
          message = s"search_conversation: cannot read conversation `${targetConvId.value}` — $reason",
          hint = Some(
            "Cross-conversation reads are allowed only against the caller's own conversation, " +
              "its parent, or one of its workers."
          )
        ))
      case Right(_) =>
        val isWalk = input.query.trim.isEmpty
        val eventsTask =
          if (isWalk) walk(context, targetConvId, input.topicId, input.limit, input.page)
          else context.sigil.searchConversationEvents(
            conversationId = targetConvId,
            query = input.query,
            topicId = input.topicId,
            limit = input.limit
          )
        eventsTask.map { events =>
          ToolResult.Success(SearchConversationOutput(
            query = input.query,
            hits = events.map(toHit),
            count = events.size
          ))
        }
    }
  }

  /**
   * Chronological-walk page over the target conversation's events.
   * Returns `limit` events starting at offset `page * limit`, in
   * timestamp-ascending order, optionally filtered by topic.
   */
  private def walk(context: ToolContext,
                   targetConvId: lightdb.id.Id[sigil.conversation.Conversation],
                   topicId: Option[lightdb.id.Id[sigil.conversation.Topic]],
                   limit: Int,
                   page: Int): Task[List[Event]] =
    context.sigil.withDB(_.conversationEventsConsistent(targetConvId)).map { all =>
      val filtered = all
        .filter(e => topicId.forall(_ == e.topicId))
        .sortBy(_.timestamp.value)
      val offset = math.max(0, page) * math.max(1, limit)
      filtered.drop(offset).take(math.max(1, limit))
    }

  private def toHit(e: Event): SearchConversationHit = {
    val snippet = e match {
      case m: Message =>
        m.content.collect { case ResponseContent.Text(t) => t }.mkString(" ").take(280)
      case tc: TopicChange => s"[topic change] ${tc.newLabel}"
      case other => other.getClass.getSimpleName
    }
    SearchConversationHit(
      eventId = e._id.value,
      timestamp = e.timestamp.value,
      participantId = e.participantId.value,
      topicId = e.topicId.value,
      eventType = e.getClass.getSimpleName,
      snippet = snippet
    )
  }
}
