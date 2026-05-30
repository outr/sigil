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
 * Universal navigation tool for paginated output produced by any
 * [[PaginatedTool]]. The agent calls this with the parent node's
 * `referenceId` (or the originating tool-call's id for the top-
 * level page) and receives the next page of typed JSON.
 *
 * Tree-aware: pass a node's `_id` (from a prior page's
 * `nodeIds`) to expand its children. Pass the tool-call's id (the
 * `callId` field on a [[JsonPagedResult]]) to walk top-level
 * siblings.
 *
 * Sigil #289 — accepts an optional `conversationId` to read from a
 * related conversation (parent or worker). When unset, defaults to
 * the caller's current conversation. Cross-conversation reads are
 * gated by [[sigil.Sigil.canReadConversation]]. */
case object NextPageTool extends Tool {
  type Input  = NextPageInput
  type Output = JsonPagedResult
  val inputRW  = summon[RW[NextPageInput]]
  val outputRW = summon[RW[JsonPagedResult]]
  val name = ToolName("next_page")
  val description =
    """Read the next page of a paginated tool result.
      |
      |Most bulk-result tools (grep, glob, bash, lsp_workspace_symbols, ...) emit a
      |first page inline + drain the rest to per-conversation storage. To read more,
      |call `next_page` with one of:
      |
      |  - the originating tool-call's `callId` (echoed on every first-page result)
      |    — returns the next sibling page at the top level
      |  - a node's `_id` from a prior page's `nodeIds` array — returns that node's
      |    children when it had `hasChildren = true`
      |
      |Also reloads context that was elided to save budget:
      |
      |  - an `event id` (shown in an elided entry's `[… next_page("<id>")]` marker)
      |    — returns that event's full content, paginated
      |  - a `summary id` — returns the list of events the summary covers (each with a
      |    snippet + its id); drill into one with `next_page("<eventId>")`
      |
      |Page indexing is zero-based. `pageSize` defaults to 50 (max 500).""".stripMargin
  override val keywords = Set("next", "page", "more", "paginate", "results", "navigate", "children", "expand")

  private val maxPageSize = 500

  override def executeResult(input: NextPageInput,
                             ctx: ToolContext): Task[ToolResult[JsonPagedResult]] = {
    val pageSize = math.max(1, math.min(input.pageSize, maxPageSize))
    val targetConvId = input.conversationId.getOrElse(ctx.conversation.id)
    val currentConvId = ctx.conversation.id
    ctx.sigil.canReadConversation(currentConvId, targetConvId).flatMap {
      case Left(reason) =>
        Task.pure(ToolResult.failure(
          message = s"next_page: cannot read conversation `${targetConvId.value}` — $reason",
          hint = Some(
            "Cross-conversation reads are allowed only against the caller's own conversation, " +
              "its parent, or one of its workers."
          )
        ))
      case Right(_) =>
        ctx.sigil.withDB(_.toolOutputs.transaction(
          _.query.filter(_.conversationKey === targetConvId.value).toList
        )).flatMap { all =>
          // Find any row matching the referenceId in this conversation;
          // its callId is what we filter by.
          val anyRow = all.find(n => n._id.value == input.referenceId || n.referenceId == input.referenceId || n.callId.value == input.referenceId)
          anyRow match {
            case None =>
              // #316 — the referenceId isn't a paginated tool output.
              // Resolve it as a ContextSummary id (browse its covered
              // events) or an Event id (paginate that event's full
              // content), so an elided `summary + id` placeholder
              // expands back to the source.
              resolveEventOrSummary(ctx, targetConvId, input.referenceId, input.page, pageSize)
            case Some(row) =>
              // `referenceId` may be either a node id (children-of-X) OR
              // a callId (top-level rows of that call). We resolve to
              // the row's actual referenceKey at query time.
              val readRef =
                if (row.callId.value == input.referenceId) input.referenceId
                else if (row._id.value == input.referenceId) row._id.value
                else input.referenceId
              PaginatedTool.readPage(
                host           = ctx.sigil,
                conversationId = targetConvId,
                callId         = row.callId,
                referenceId    = readRef,
                page           = input.page,
                pageSize       = pageSize
              ).map(ToolResult.Success(_))
          }
        }
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
    * agent drills into one via `next_page("<eventId>")`. */
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
