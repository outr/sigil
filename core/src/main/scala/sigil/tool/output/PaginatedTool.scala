package sigil.tool.output

import fabric.rw.*
import lightdb.filter.FilterExtras
import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.provider.Mode
import sigil.tool.{Tool, ToolExample, ToolInput, ToolName, ToolResult}

/**
 * Authoring base for tools whose output is a paginated tree of typed
 * payloads. Tool authors implement [[executeStream]] returning
 * `rapid.Stream[Node[A]]`; the framework drains the stream lazily into
 * [[sigil.db.SigilDB.toolOutputs]] and resolves to a [[JsonPagedResult]]
 * carrying the first page. [[Tool.execute]] then emits the settling
 * [[sigil.signal.ToolDelta]] folding the first-page payload onto the
 * originating [[sigil.event.ToolInvoke]]; subsequent pages are reached
 * through [[NextPageTool]] / [[QueryToolOutputTool]].
 *
 * The specialised sub-shape of [[Tool]] for genuinely-unbounded bulk
 * output: one node at a time crosses the drain pipeline, the rest lives
 * in RocksDB. Tree shape — a `Node[A]` declares `hasChildren = true` and
 * a lazy `children: Stream[Node[A]]`; the framework drains depth-first,
 * child rows referencing their parent by id so `next_page(parentNodeId)`
 * returns them.
 *
 * Compose-friendly: another tool can call
 * `MyPaginatedTool.invokeFirstPage(input, ctx)` and get the first-page
 * `JsonPagedResult` directly, with the call's rows already drained.
 */
abstract class PaginatedTool[In <: ToolInput, A](
  override val name: ToolName,
  description0: String,
  override val examples: List[ToolExample] = Nil,
  override val modes: Set[Id[Mode]] = Set.empty,
  override val space: SpaceId = GlobalSpace,
  override val keywords: Set[String] = Set.empty,
  override val createdBy: Option[ParticipantId] = None,
  /** Number of rows returned in the first-page emission. The
    * agent pages past this via `next_page`. Default 50. */
  val firstPageSize: Int = 50
)(using inputRwEv: RW[In], payloadRwEv: RW[A]) extends Tool {

  type Input  = In
  type Output = JsonPagedResult

  /** The author-supplied description before the standardized
    * navigation footer is appended. */
  protected val authorDescription: String = description0

  /** Effective description for the agent-facing schema and wire
    * requests — augmented with the standard navigation footer so the
    * agent always knows how to drill into the returned tree. Marked
    * `final` — the navigation contract is part of [[PaginatedTool]]'s
    * identity. */
  final override val description: String =
    authorDescription.stripTrailing + PaginatedTool.PaginationFooter

  override val inputRW: RW[In] = inputRwEv
  override val outputRW: RW[JsonPagedResult] = summon[RW[JsonPagedResult]]

  /** RW for the per-item payload type. Used at drain time to
    * serialise each node into `ToolOutputNode.payload`, and at
    * read time to decode rows into typed `PagedResult[A].items`. */
  val payloadRW: RW[A] = payloadRwEv

  /** PaginatedTool by definition emits paginated output — every
    * concrete subclass is iterative by construction. */
  final override val paginate: Boolean = true

  /** Tool authors implement this. Returns a lazy stream of
    * top-level nodes; the framework drains them in order. */
  protected def executeStream(input: In, context: TurnContext): Stream[Node[A]]

  /** Composition entry — runs the stream, drains to the DB, and
    * returns the first-page result so a caller (the framework's
    * execute path, or another tool that wraps this one) can use
    * it directly. */
  def invokeFirstPage(input: In, context: TurnContext): Task[JsonPagedResult] =
    drainAndFirstPage(input, context)

  /** Drains the typed stream into the DB and resolves to the
    * first-page [[JsonPagedResult]]; [[Tool.execute]] emits the
    * settling [[sigil.signal.ToolDelta]] from it. A drain-time crash
    * maps to a recoverable [[ToolResult.Failure]] via [[Tool]]'s
    * framework error path — no per-tool `handleError` needed. */
  override def executeResult(input: In, context: TurnContext): Task[ToolResult[JsonPagedResult]] =
    drainAndFirstPage(input, context).map(ToolResult.success)

  /** Drain the stream into `db.toolOutputs`, then return the
    * first page. Rows are keyed by
    * `(conversationId, callId, referenceId, ordinal)` so
    * `next_page` reads via the compound index. */
  private def drainAndFirstPage(input: In, context: TurnContext): Task[JsonPagedResult] = {
    val convId = context.conversation.id
    val callId: Id[Event] = context.currentToolInvokeId.getOrElse(Event.id())

    def drainOne(node: Node[A],
                 referenceId: String,
                 level: Int,
                 ordinal: Int): Task[Unit] = {
      val payloadJson = payloadRW.read(node.payload)
      val row = ToolOutputNode(
        conversationId = convId,
        callId         = callId,
        referenceId    = referenceId,
        level          = level,
        ordinal        = ordinal,
        hasChildren    = node.hasChildren,
        payload        = payloadJson
      )
      context.sigil.withDB(_.toolOutputs.transaction(_.upsert(row))).unit.flatMap { _ =>
        if (node.hasChildren)
          drainStream(node.children, parent = row._id.value, level = level + 1)
        else Task.unit
      }
    }

    def drainStream(stream: Stream[Node[A]], parent: String, level: Int): Task[Unit] = {
      val counter = new java.util.concurrent.atomic.AtomicInteger(0)
      stream.evalTap { node =>
        val i = counter.getAndIncrement()
        drainOne(node, parent, level, i)
      }.drain
    }

    drainStream(executeStream(input, context), parent = callId.value, level = 0).flatMap { _ =>
      PaginatedTool.readPage(context.sigil, convId, callId, callId.value, page = 0, pageSize = firstPageSize)
    }
  }
}

object PaginatedTool {

  /** Standardized footer auto-appended to every paginated tool's
    * description so the agent always knows how to drill into the
    * returned tree. Centralised here so the phrasing stays consistent
    * and updates to the navigation API propagate to every subclass. */
  val PaginationFooter: String =
    """
      |
      |— Paginated output —
      |This tool's first-page result includes `callId`, `hasMore`, and `nodeIds`.
      |Walk the tree with `next_page(referenceId)`:
      |  • `referenceId = callId`     → next sibling page at this level
      |  • `referenceId = <nodeId>`   → expand a node whose `hasChildren = true`
      |Use `query_tool_output(callId, containsText?, level?)` for a flat
      |cross-tree filter when you want matches anywhere in the result
      |at once (e.g. "all line-matches that contain 'reset_password').""".stripMargin

  /** Read a page of paginated output from `db.toolOutputs`. Used
    * by [[PaginatedTool]]'s first-page return, [[NextPageTool]],
    * and [[QueryToolOutputTool]]. */
  def readPage(host: _root_.sigil.Sigil,
               conversationId: Id[_root_.sigil.conversation.Conversation],
               callId: Id[Event],
               referenceId: String,
               page: Int,
               pageSize: Int): Task[JsonPagedResult] =
    host.withDB(_.toolOutputs.transaction(_.query.filter(n =>
      n.conversationKey === conversationId.value &&
        n.callKey === callId.value &&
        n.referenceKey === referenceId
    ).toList)).map { matchingRows =>
      val matching = matchingRows.sortBy(_.ordinal)
      val total = matching.size
      val safePage = math.max(0, page)
      val window = matching.slice(safePage * pageSize, (safePage + 1) * pageSize)
      JsonPagedResult(
        items       = window.map(_.payload).toList,
        hasMore     = ((safePage + 1) * pageSize) < total,
        page        = safePage,
        pageSize    = pageSize,
        referenceId = referenceId,
        callId      = callId,
        totalCount  = Some(total),
        nodeIds     = window.map(_._id.value).toList,
        hasChildren = window.map(_.hasChildren).toList
      )
    }
}
