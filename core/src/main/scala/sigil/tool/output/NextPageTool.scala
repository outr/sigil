package sigil.tool.output

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.event.Event
import sigil.tool.{Tool, ToolName, ToolResult}

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
              Task.pure(ToolResult.Success(JsonPagedResult(
                items       = Nil,
                hasMore     = false,
                page        = input.page,
                pageSize    = pageSize,
                referenceId = input.referenceId,
                callId      = sigil.event.Event.id(),
                totalCount  = Some(0)
              )))
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
}
