package sigil.tool.output

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.event.{Event, MessageRole, ToolOutcome, ToolResults}
import sigil.participant.ParticipantId
import sigil.provider.Mode
import sigil.signal.EventState
import sigil.tool.{Tool, ToolExample, ToolInput, ToolName}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.reflect.ClassTag

/**
 * Authoring base for tools whose output is a paginated tree of
 * typed payloads. Tool authors implement
 * [[executeStream]] returning `rapid.Stream[Node[A]]`; the
 * framework drains the stream lazily into
 * [[sigil.db.SigilDB.toolOutputs]], emits a [[ToolResults]] event
 * carrying the first page inline as `typed` JSON, and exposes
 * subsequent pages through [[NextPageTool]] /
 * [[QueryToolOutputTool]].
 *
 * Replaces the prior "externalize the whole blob to a pointer"
 * design — the agent navigates pages via stable navigation tools
 * instead of fetching opaque output ids. Memory-bounded by
 * construction: one node at a time crosses the drain pipeline,
 * the rest lives in RocksDB.
 *
 * Tree shape: a `Node[A]` declares `hasChildren = true` and a
 * lazy `children: Stream[Node[A]]`. The framework drains the
 * tree depth-first; child rows reference their parent by id, so
 * `next_page(parentNodeId)` returns the children when the agent
 * wants to expand.
 *
 * Compose-friendly: another tool can call
 * `MyPaginatedTool.invokeFirstPage(input, ctx)` and get the
 * first-page `JsonPagedResult` directly, with the call's rows
 * already drained — useful for tools that wrap another's output.
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
  val firstPageSize: Int = 50,
  /** Per-row TTL. Drained rows expire after this duration. The
    * [[sigil.maintenance.ToolOutputExpirationSweep]] task reclaims
    * them. Default 30 minutes. */
  val rowTtl: FiniteDuration = 30.minutes
)(using ct: ClassTag[In], inputRwEv: RW[In], outputRwEv: RW[A]) extends Tool {

  /** The author-supplied description before the standardized
    * navigation footer is appended. Subclasses rarely need this —
    * exposed for diagnostic / round-trip tooling. */
  protected val authorDescription: String = description0

  /** Effective description for the agent-facing schema and wire
    * requests. Sigil bug #202 — every paginated tool's description
    * is augmented with the same navigation footer so the agent
    * always knows how to drill into the returned tree using
    * [[NextPageTool]] / [[QueryToolOutputTool]] regardless of
    * which `PaginatedTool` subclass produced the result.
    *
    * Marked `final` — the navigation contract is part of
    * [[PaginatedTool]]'s identity; opting out would mean the tool
    * isn't actually paginated. */
  final override val description: String =
    authorDescription.stripTrailing + PaginatedTool.PaginationFooter

  override val inputRW: RW[In] = inputRwEv

  /** RW for the per-item payload type. Used at drain time to
    * serialise each node into `ToolOutputNode.payload`, and at
    * read time to decode rows into typed `PagedResult[A].items`. */
  val payloadRW: RW[A] = outputRwEv

  /** Tool authors implement this. Returns a lazy stream of
    * top-level nodes; the framework drains them in order. */
  protected def executeStream(input: In, context: TurnContext): Stream[Node[A]]

  /** Composition entry — runs the stream, drains to the DB, and
    * returns the first-page result so a caller (the framework's
    * execute path, or another tool that wraps this one) can use
    * it directly. */
  def invokeFirstPage(input: In, context: TurnContext): Task[JsonPagedResult] =
    drainAndFirstPage(input, context)

  /** PaginatedTool by definition emits paginated output — every
    * concrete subclass is iterative by construction. Sigil bug
    * #201. */
  final override def paginate: Boolean = true

  /** Glue — implements the [[Tool]] trait's `Stream[Event]`
    * contract by draining the typed stream into the DB and
    * emitting a single [[ToolResults]] event with the first page
    * inline. */
  final override def execute(input: ToolInput, context: TurnContext): Stream[Event] = {
    if (!ct.runtimeClass.isInstance(input)) Stream.empty
    else {
      val typedInput = input.asInstanceOf[In]
      Stream.force(
        drainAndFirstPage(typedInput, context).map { page =>
          Stream.emit[Event](ToolResults(
            schemas        = Nil,
            participantId  = context.caller,
            conversationId = context.conversation.id,
            topicId        = context.conversation.currentTopicId,
            outcome        = ToolOutcome.Success,
            typed          = Some(summon[RW[JsonPagedResult]].read(page)),
            state          = EventState.Complete,
            role           = MessageRole.Tool
          ))
        }.handleError { t =>
          Task.pure(Stream.emit[Event](ToolResults(
            schemas        = Nil,
            participantId  = context.caller,
            conversationId = context.conversation.id,
            topicId        = context.conversation.currentTopicId,
            outcome        = ToolOutcome.Failure(
              reason       = s"${name.value} failed: ${t.getClass.getSimpleName}: ${t.getMessage}",
              recoverable  = true
            ),
            summary        = Some(s"${name.value} failed: ${t.getClass.getSimpleName}: ${t.getMessage}"),
            state          = EventState.Complete,
            role           = MessageRole.Tool
          )))
        }
      )
    }
  }

  /** Drain the stream into `db.toolOutputs`, then return the
    * first page. Rows are keyed by
    * `(conversationId, callId, referenceId, ordinal)` so
    * `next_page` reads via the compound index. */
  private def drainAndFirstPage(input: In, context: TurnContext): Task[JsonPagedResult] = {
    val convId = context.conversation.id
    val callId: Id[Event] = context.currentToolInvokeId.getOrElse(Event.id())
    val now = System.currentTimeMillis()
    val expiresAt = Timestamp(now + rowTtl.toMillis)

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
        payload        = payloadJson,
        expiresAt      = expiresAt
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
    * returned tree. Sigil bug #202 — centralised here so the
    * phrasing stays consistent and updates to the navigation API
    * propagate to every subclass automatically. */
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
    host.withDB(_.toolOutputs.transaction(_.list)).map { all =>
      val matching = all
        .filter(n => n.conversationId == conversationId && n.callId == callId && n.referenceId == referenceId)
        .sortBy(_.ordinal)
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
