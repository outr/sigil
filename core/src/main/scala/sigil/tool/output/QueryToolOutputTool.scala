package sigil.tool.output

import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.filter.FilterExtras
import lightdb.id.Id
import rapid.Task
import sigil.tool.ToolContext
import sigil.event.Event
import sigil.tool.{Tool, ToolName, ToolResult}

/**
 * Filtered + sorted cross-tree query over one tool-call's
 * bulk output — the bounded-inspection rung of the reference ladder.
 * Use this when the agent needs a specific slice (`all files with >10
 * matches`, `nodes whose payload mentions 'reset_password'`, etc.)
 * rather than a whole-set summary ([[SummarizeOutputTool]]) or an action
 * ([[sigil.tool.output]] consumers like `dispatch_workers`).
 *
 * Sigil #289 — accepts an optional `conversationId` to read from a
 * related conversation (parent or worker). When unset, defaults to
 * the caller's current conversation. Cross-conversation reads are
 * gated by [[sigil.Sigil.canReadConversation]].
 */
case object QueryToolOutputTool extends Tool {
  type Input  = QueryToolOutputInput
  type Output = JsonPagedResult
  val inputRW  = summon[RW[QueryToolOutputInput]]
  val outputRW = summon[RW[JsonPagedResult]]
  val name = ToolName("query_tool_output")
  val description =
    """Inspect specific entries of a bulk tool result by reference — filtered + paginated.
      |
      |`callId` is required — every bulk-result tool's first page echoes the originating
      |call's id in its `callId` field. The query scope is that one call. Use this for a
      |bounded look at specific rows; to summarize the whole set use `summarize_output`,
      |to act on it use `dispatch_workers`.
      |
      |Optional filters:
      |  - `containsText` — case-insensitive substring filter over the row's rendered JSON
      |  - `level`         — limit to a specific tree depth (0 = top-level, 1 = direct children)
      |
      |Pagination is zero-indexed; `pageSize` defaults to 50 (max 500).""".stripMargin
  override val keywords = Set("query", "filter", "find", "search", "tool", "output", "paginate", "where", "results")

  private val maxPageSize = 500

  override def executeResult(input: QueryToolOutputInput,
                             ctx: ToolContext): Task[ToolResult[JsonPagedResult]] = {
    val pageSize = math.max(1, math.min(input.pageSize, maxPageSize))
    val safePage = math.max(0, input.page)
    val targetConvId = input.conversationId.getOrElse(ctx.conversation.id)
    val currentConvId = ctx.conversation.id

    ctx.sigil.canReadConversation(currentConvId, targetConvId).flatMap {
      case Left(reason) =>
        Task.pure(ToolResult.failure(
          message = s"query_tool_output: cannot read conversation `${targetConvId.value}` — $reason",
          hint = Some(
            "Cross-conversation reads are allowed only against the caller's own conversation, " +
              "its parent, or one of its workers."
          )
        ))
      case Right(_) =>
        ctx.sigil.withDB(_.toolOutputs.transaction(_.query.filter(n =>
          n.conversationKey === targetConvId.value && n.callKey === input.callId
        ).toList)).map { callRows =>
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
          ToolResult.Success(JsonPagedResult(
            items       = window.map(_.payload).toList,
            hasMore     = ((safePage + 1) * pageSize) < total,
            page        = safePage,
            pageSize    = pageSize,
            referenceId = input.callId,
            callId      = callIdEvent,
            totalCount  = Some(total),
            nodeIds     = window.map(_._id.value).toList,
            hasChildren = window.map(_.hasChildren).toList
          ))
        }
    }
  }
}
