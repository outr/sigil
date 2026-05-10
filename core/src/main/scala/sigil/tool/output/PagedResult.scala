package sigil.tool.output

import fabric.Json
import fabric.rw.*
import lightdb.id.Id
import sigil.event.Event

/**
 * One page of paginated tool output. Returned by the initial
 * tool call (top-level page) and by [[NextPageTool]] /
 * [[QueryToolOutputTool]] for subsequent navigation.
 *
 *   - `items` — typed payloads materialised from each row's
 *     `payload` JSON. Tools whose output type is `A` see
 *     `items: List[A]`; the framework decodes via the tool's
 *     declared `RW[A]`.
 *   - `hasMore` — true when more rows exist past this page
 *     (either pending drain or unread). Agents page until
 *     `hasMore = false`.
 *   - `page` — zero-indexed page number (echoed for client
 *     bookkeeping).
 *   - `pageSize` — number of rows requested for this page.
 *   - `referenceId` — the parent the agent paged against. Echoed
 *     so the next call can be issued with the same value.
 *   - `callId` — the originating [[sigil.event.ToolInvoke]] id.
 *     Used as the top-level `referenceId` and by
 *     [[QueryToolOutputTool]] for cross-tree queries.
 *   - `totalCount` — total rows under `referenceId` when known.
 *     `None` while the drainer is still streaming.
 *   - `nodeIds` — opaque per-item ids for `next_page` against a
 *     specific node's children. Aligned with `items` 1:1.
 *   - `hasChildren` — per-item flag aligned with `items`. Tells
 *     the agent which items can be expanded.
 */
final case class PagedResult[A](items: List[A],
                                hasMore: Boolean,
                                page: Int,
                                pageSize: Int,
                                referenceId: String,
                                callId: Id[Event],
                                totalCount: Option[Int] = None,
                                nodeIds: List[String] = Nil,
                                hasChildren: List[Boolean] = Nil)

// No generic RW for PagedResult[A] — tool authors use the typed
// shape for their own composition; the wire emission goes through
// [[JsonPagedResult]] which is concrete.

/**
 * Wire-friendly variant of [[PagedResult]] holding payloads as
 * raw JSON. Used internally by [[NextPageTool]] /
 * [[QueryToolOutputTool]] which don't know the per-call's tool
 * shape at compile time — the agent gets typed JSON the LLM
 * pretty-prints natively. Tool authors stay typed; navigation
 * tools stay generic.
 */
final case class JsonPagedResult(items: List[Json],
                                 hasMore: Boolean,
                                 page: Int,
                                 pageSize: Int,
                                 referenceId: String,
                                 callId: Id[Event],
                                 totalCount: Option[Int] = None,
                                 nodeIds: List[String] = Nil,
                                 hasChildren: List[Boolean] = Nil) derives RW
