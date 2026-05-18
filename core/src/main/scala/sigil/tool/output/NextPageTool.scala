package sigil.tool.output

import rapid.Task
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolName, TypedOutputTool}

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
 * Scoped to the current conversation — rows from other
 * conversations are not reachable.
 */
case object NextPageTool
  extends TypedOutputTool[NextPageInput, JsonPagedResult](
    name = ToolName("next_page"),
    description =
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
      |Page indexing is zero-based. `pageSize` defaults to 50 (max 500).""".stripMargin,
    keywords = Set("next", "page", "more", "paginate", "results", "navigate", "children", "expand")
  ) {
  override def paginate: Boolean = false

  private val maxPageSize = 500

  override protected def executeTyped(input: NextPageInput, ctx: TurnContext): Task[JsonPagedResult] = {
    val pageSize = math.max(1, math.min(input.pageSize, maxPageSize))
    val convId = ctx.conversation.id
    ctx.sigil.withDB(_.toolOutputs.transaction(_.list)).flatMap { all =>
      // Find any row matching the referenceId in this conversation;
      // its callId is what we filter by.
      val anyRow = all.find(n =>
        n.conversationId == convId &&
          (n._id.value == input.referenceId || n.referenceId == input.referenceId || n.callId.value == input.referenceId))
      anyRow match {
        case None =>
          Task.pure(JsonPagedResult(
            items = Nil,
            hasMore = false,
            page = input.page,
            pageSize = pageSize,
            referenceId = input.referenceId,
            callId = sigil.event.Event.id(),
            totalCount = Some(0)
          ))
        case Some(row) =>
          // `referenceId` may be either a node id (children-of-X) OR
          // a callId (top-level rows of that call). We resolve to
          // the row's actual referenceKey at query time.
          val readRef =
            if (row.callId.value == input.referenceId) input.referenceId
            else if (row._id.value == input.referenceId) row._id.value
            else input.referenceId
          PaginatedTool.readPage(
            host = ctx.sigil,
            conversationId = convId,
            callId = row.callId,
            referenceId = readRef,
            page = input.page,
            pageSize = pageSize
          )
      }
    }
  }
}
