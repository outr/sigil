package sigil.tool.output

import fabric.io.JsonFormatter
import lightdb.id.Id
import rapid.Task
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolName, TypedOutputTool}

/**
 * Filtered + sorted cross-tree query over one tool-call's
 * paginated output. Complements [[NextPageTool]] (the
 * level-by-level walker) — use this when the agent needs richer
 * access (`all files with >10 matches`, `nodes whose payload
 * mentions 'reset_password'`, etc.).
 *
 * Scoped to the current conversation — rows from other
 * conversations are not reachable.
 */
case object QueryToolOutputTool extends TypedOutputTool[QueryToolOutputInput, JsonPagedResult](
  name = ToolName("query_tool_output"),
  description =
    """Query a paginated tool result with filters and pagination beyond what `next_page` exposes.
      |
      |`callId` is required — every `PaginatedTool` first-page result echoes the
      |originating call's id in its `callId` field. The query scope is that one call.
      |
      |Optional filters:
      |  - `containsText` — case-insensitive substring filter over the row's rendered JSON
      |  - `level`         — limit to a specific tree depth (0 = top-level, 1 = direct children)
      |
      |Pagination is zero-indexed; `pageSize` defaults to 50 (max 500).""".stripMargin,
  keywords = Set("query", "filter", "find", "search", "tool", "output", "paginate", "where", "results")
) {
  override def paginate: Boolean = false


  private val maxPageSize = 500

  override protected def executeTyped(input: QueryToolOutputInput, ctx: TurnContext): Task[JsonPagedResult] = {
    val pageSize = math.max(1, math.min(input.pageSize, maxPageSize))
    val safePage = math.max(0, input.page)
    val convId   = ctx.conversation.id

    ctx.sigil.withDB(_.toolOutputs.transaction(_.list)).map { all =>
      val callRows = all.filter(n => n.conversationId == convId && n.callId.value == input.callId)
      val filtered = callRows.filter { n =>
        val levelOk = input.level.forall(_ == n.level)
        val textOk  = input.containsText match {
          case None    => true
          case Some(q) =>
            val needle = q.toLowerCase
            JsonFormatter.Compact(n.payload).toLowerCase.contains(needle)
        }
        levelOk && textOk
      }.sortBy(n => (n.level, n.ordinal))

      val total = filtered.size
      val window = filtered.slice(safePage * pageSize, (safePage + 1) * pageSize)
      val callIdEvent: Id[Event] = window.headOption.map(_.callId).getOrElse(Id[Event](input.callId))
      JsonPagedResult(
        items       = window.map(_.payload).toList,
        hasMore     = ((safePage + 1) * pageSize) < total,
        page        = safePage,
        pageSize    = pageSize,
        referenceId = input.callId,
        callId      = callIdEvent,
        totalCount  = Some(total),
        nodeIds     = window.map(_._id.value).toList,
        hasChildren = window.map(_.hasChildren).toList
      )
    }
  }
}
