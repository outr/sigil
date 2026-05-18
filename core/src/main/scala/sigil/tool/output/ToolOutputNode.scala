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
 *   - `conversationId` — primary scope. Conversation-close
 *     deletes every row with the matching id.
 *   - `callId` — the [[sigil.event.ToolInvoke]] id this output
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
 *   - `hasChildren` — whether the agent can `next_page` against
 *     this row's `_id` to expand.
 *   - `payload` — the typed per-item value, serialised through
 *     the tool's `RW[A]` at drain time.
 *   - `expiresAt` — per-row TTL. The
 *     [[sigil.maintenance.ToolOutputExpirationSweep]] task
 *     reclaims rows past this timestamp. Default 30 minutes from
 *     insert.
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
                                expiresAt: Timestamp,
                                _id: Id[ToolOutputNode] = ToolOutputNode.id())
  extends RecordDocument[ToolOutputNode]

object ToolOutputNode extends RecordDocumentModel[ToolOutputNode] with JsonConversion[ToolOutputNode] {
  implicit override def rw: RW[ToolOutputNode] = RW.gen

  override def id(value: String = Unique()): Id[ToolOutputNode] = Id(value)

  /**
   * Conversation scope — primary filter for every read path.
   */
  val conversationKey: I[String] = field.index("conversationKey", _.conversationId.value)

  /**
   * Per-call scope — pagination queries narrow by (conversation, call, reference).
   */
  val callKey: I[String] = field.index("callKey", _.callId.value)

  /**
   * Parent-id scope — `next_page(referenceId)` reads rows where
   * `referenceKey === referenceId`. Top-level rows carry the
   * tool-call's id as their referenceKey.
   */
  val referenceKey: I[String] = field.index("referenceKey", _.referenceId)

  /**
   * Sibling ordering — pagination reads sort by this ascending.
   */
  val ordinalKey: I[Int] = field.index("ordinalKey", _.ordinal)

  /**
   * Expiration index for the TTL sweeper.
   */
  val expiresAtKey: I[Long] = field.index("expiresAtKey", _.expiresAt.value)
}
