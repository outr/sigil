package sigil.tool.output

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.conversation.{ContextFrame, ContextSummary, Conversation, ToolCallState}
import sigil.tool.ToolContext
import sigil.event.{Event, Message}
import sigil.tool.{Tool, ToolName, ToolResult}
import sigil.tool.model.ResponseContent

/**
 * Reload content that context virtualization (#316) elided to save
 * budget, by id. When the curator trims a frame to a gist, it leaves a
 * `[… reload_content("<id>")]` marker; the agent calls this with that id
 * to get the full content back:
 *
 *   - an `event id` — returns that event's full content, paginated into
 *     chunks.
 *   - a `summary id` — returns the list of events the summary covers
 *     (each with a snippet + its id); drill into one with
 *     `reload_content("<eventId>")`.
 *
 * This is purely the #316 reload-by-id path. Tool *output* (grep / glob /
 * … results) is no longer walked page-by-page — operate on it by
 * reference instead (`summarize_output`, `query_tool_output`,
 * `dispatch_workers`).
 *
 * Sigil #289 — accepts an optional `conversationId` to read from a
 * related conversation (parent or worker). When unset, defaults to the
 * caller's current conversation. Cross-conversation reads are gated by
 * [[sigil.Sigil.canReadConversation]]. */
case object ReloadContentTool extends Tool {
  type Input  = ReloadContentInput
  type Output = JsonPagedResult
  val inputRW  = summon[RW[ReloadContentInput]]
  val outputRW = summon[RW[JsonPagedResult]]
  val name = ToolName("reload_content")
  val description =
    """Reload full content that was elided from your context to save budget.
      |
      |When an entry shows a `[… reload_content("<id>")]` marker, call this with that id:
      |
      |  - an `event id` — returns that event's full content, paginated
      |  - a `summary id` — returns the list of events the summary covers (each with a
      |    snippet + its id); drill into one with `reload_content("<eventId>")`
      |
      |Page indexing is zero-based. `pageSize` defaults to 50 (max 500).""".stripMargin
  override val keywords = Set("reload", "expand", "content", "elided", "restore", "full", "rehydrate")

  private val maxPageSize = 500

  override def executeResult(input: ReloadContentInput,
                             ctx: ToolContext): Task[ToolResult[JsonPagedResult]] = {
    val pageSize = math.max(1, math.min(input.pageSize, maxPageSize))
    val targetConvId = input.conversationId.getOrElse(ctx.conversation.id)
    val currentConvId = ctx.conversation.id
    ctx.sigil.canReadConversation(currentConvId, targetConvId).flatMap {
      case Left(reason) =>
        Task.pure(ToolResult.failure(
          message = s"reload_content: cannot read conversation `${targetConvId.value}` — $reason",
          hint = Some(
            "Cross-conversation reads are allowed only against the caller's own conversation, " +
              "its parent, or one of its workers."
          )
        ))
      case Right(_) =>
        resolveEventOrSummary(ctx, targetConvId, input.referenceId, input.page, pageSize)
    }
  }

  /** Characters per chunk when paginating a single event's content. */
  private val eventChunkChars = 4000

  private def emptyResult(referenceId: String, page: Int, pageSize: Int): ToolResult[JsonPagedResult] =
    ToolResult.Success(JsonPagedResult(
      items = Nil, hasMore = false, page = page, pageSize = pageSize,
      referenceId = referenceId, callId = Event.id(), totalCount = Some(0)))

  /** Reload-by-id (#316): resolve a non-tool-output `referenceId` as a
    * [[ContextSummary]] (browse its covered events) or an [[Event]]
    * (paginate its full content). Both stay scoped to `convId`. */
  private def resolveEventOrSummary(ctx: ToolContext,
                                    convId: Id[Conversation],
                                    referenceId: String,
                                    page: Int,
                                    pageSize: Int): Task[ToolResult[JsonPagedResult]] =
    ctx.sigil.withDB(_.summaries.transaction(_.get(Id[ContextSummary](referenceId)))).flatMap {
      case Some(summary) if summary.conversationId == convId =>
        summaryPage(ctx, summary, referenceId, page, pageSize)
      case _ =>
        ctx.sigil.withDB(_.eventsTransaction(convId)(_.get(Id[Event](referenceId)))).map {
          case Some(ev) if ev.conversationId == convId =>
            eventContentPage(ev, referenceId, page, pageSize)
          case _ => emptyResult(referenceId, page, pageSize)
        }
    }

  /** A browsable index of a summary's covered events: each item names
    * the event id + a snippet, with `nodeIds` carrying the ids so the
    * agent drills into one via `reload_content("<eventId>")`. */
  private def summaryPage(ctx: ToolContext,
                          summary: ContextSummary,
                          referenceId: String,
                          page: Int,
                          pageSize: Int): Task[ToolResult[JsonPagedResult]] = {
    val ids = summary.coversEventIds
    val start = page * pageSize
    val window = ids.slice(start, start + pageSize)
    if (window.isEmpty) Task.pure(emptyResult(referenceId, page, pageSize))
    else ctx.sigil.withDB(_.eventsTransaction(summary.conversationId) { tx =>
      Task.sequence(window.map(tx.get))
    }).map { loaded =>
      val byId = loaded.flatten.map(e => e._id.value -> e).toMap
      val items = window.map { id =>
        val snippet = byId.get(id.value).map(e => eventContentText(e).take(160)).getOrElse("")
        fabric.obj("eventId" -> fabric.str(id.value), "snippet" -> fabric.str(snippet))
      }
      ToolResult.Success(JsonPagedResult(
        items = items, hasMore = start + pageSize < ids.size, page = page, pageSize = pageSize,
        referenceId = referenceId, callId = Event.id(), totalCount = Some(ids.size),
        nodeIds = window.map(_.value), hasChildren = window.map(_ => true)))
    }
  }

  /** Paginate a single event's full content into fixed-size chunks. */
  private def eventContentPage(ev: Event,
                               referenceId: String,
                               page: Int,
                               pageSize: Int): ToolResult[JsonPagedResult] = {
    val chunks = eventContentText(ev).grouped(eventChunkChars).toVector
    val start = page * pageSize
    val window = chunks.slice(start, start + pageSize)
    ToolResult.Success(JsonPagedResult(
      items = window.map(c => fabric.str(c)).toList, hasMore = start + pageSize < chunks.size,
      page = page, pageSize = pageSize, referenceId = referenceId, callId = ev._id,
      totalCount = Some(chunks.size)))
  }

  /** The full rendered text an event carries — preferring its inlined
    * frame (which retains full content even when the per-turn render
    * elided it), falling back to a Message's text blocks. */
  private def eventContentText(ev: Event): String = ev.contextFrame match {
    case Some(t: ContextFrame.Text)     => t.content
    case Some(s: ContextFrame.System)   => s.content
    case Some(tc: ContextFrame.ToolCall) => tc.state match {
      case ToolCallState.Complete(c, _) => c
      case _                            => ""
    }
    case _ => ev match {
      case m: Message => m.content.collect { case ResponseContent.Text(t) => t }.mkString("\n")
      case _          => ""
    }
  }
}
