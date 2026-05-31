package sigil.tool.output

import fabric.Json
import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Unique
import sigil.conversation.Conversation
import sigil.event.Event

/**
 * Per-row record materialising one [[Node]] from a tool's
 * paginated output stream. Tools that extend [[PaginatedTool]]
 * stream `Node[A]` values; the framework drains the stream into
 * one of these rows per node, indexed for
 * `(conversationId, callId, referenceId, ordinal)`-shaped queries.
 *
 * Containers are immutable. A `(conversationId, callId)` pair
 * identifies one container; rows are added by the producer once
 * and read by consumers / filter tools forever after. Producers
 * that derive a new container from an existing one (`filter`,
 * `map`, etc.) write a fresh set of rows under a new `callId`
 * — the source rows are untouched.
 *
 *   - `conversationId` — primary scope. Conversation-close
 *     deletes every row with the matching id.
 *   - `callId` — the [[sigil.event.ToolInvoke]] id this container
 *     belongs to. Multiple `(callId, referenceId)` pairs allowed
 *     per conversation when tools produce trees.
 *   - `referenceId` — parent node's id, or `callId.value` for
 *     top-level rows. The compound `(conversationId, callId,
 *     referenceId, ordinal)` index makes paginated reads O(log
 *     N + pageSize).
 *   - `level` — 0 = top-level, 1 = direct child of top, etc.
 *     Surfaced to clients for tree-aware rendering.
 *   - `ordinal` — sibling order. Stable across pagination — page
 *     N returns rows `[N*pageSize, (N+1)*pageSize)`.
 *   - `hasChildren` — whether the agent can `query_tool_output` against
 *     this row's `_id` to expand.
 *   - `payload` — the typed per-item value, serialised through
 *     the tool's `RW[A]` at drain time.
 *   - `pinned` — when `true`, the conversation-level container
 *     cleanup skips this row. Toggled via `pin_container` /
 *     `unpin_container`. Defaults to `false`.
 *   - `expiresAt` — optional per-row TTL retained for legacy rows
 *     persisted by earlier framework versions. New containers
 *     leave it `None`; cleanup is conversation-level (age + size)
 *     rather than per-row.
 */
final case class ToolOutputNode(conversationId: Id[Conversation],
                                callId: Id[Event],
                                referenceId: String,
                                level: Int,
                                ordinal: Int,
                                hasChildren: Boolean,
                                payload: Json,
                                created: Timestamp = Timestamp(),
                                modified: Timestamp = Timestamp(),
                                pinned: Boolean = false,
                                expiresAt: Option[Timestamp] = None,
                                _id: Id[ToolOutputNode] = ToolOutputNode.id())
  extends RecordDocument[ToolOutputNode]

object ToolOutputNode extends RecordDocumentModel[ToolOutputNode] with JsonConversion[ToolOutputNode] {
  implicit override def rw: RW[ToolOutputNode] = RW.gen

  override def id(value: String = Unique()): Id[ToolOutputNode] = Id(value)

  /** Conversation scope — primary filter for every read path. */
  val conversationKey: I[String] = field.index("conversationKey", _.conversationId.value)

  /** Per-call scope — pagination queries narrow by (conversation, call, reference). */
  val callKey: I[String] = field.index("callKey", _.callId.value)

  /** Parent-id scope — `query_tool_output(referenceId)` reads rows where
    * `referenceKey === referenceId`. Top-level rows carry the
    * tool-call's id as their referenceKey. */
  val referenceKey: I[String] = field.index("referenceKey", _.referenceId)

  /** Sibling ordering — pagination reads sort by this ascending. */
  val ordinalKey: I[Int] = field.index("ordinalKey", _.ordinal)

  /** Pin flag — the conversation-level cleanup walks pinned-false
    * rows only. */
  val pinnedKey: I[Boolean] = field.index("pinnedKey", _.pinned)

  /** Created index for the conversation-level cleanup pass. */
  val createdAtKey: I[Long] = field.index("createdAtKey", _.created.value)
}
