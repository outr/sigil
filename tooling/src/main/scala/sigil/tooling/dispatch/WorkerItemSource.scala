package sigil.tooling.dispatch

import fabric.Json
import fabric.rw.*
import lightdb.id.Id
import sigil.event.Event

/**
 * How [[DispatchWorkersTool]] populates the worker-item list before
 * running the pipeline against each item. Four shapes, all
 * persistable on the typed input so a `confirmed = false` scope
 * preview round-trips and the same input drives the dispatch.
 *
 *   - [[FromList]]    — caller hands the list inline. Best when the
 *     items are short / known upfront.
 *   - [[FromCall]]    — adapter loads the persisted paginated output
 *     of a prior tool call (by [[sigil.event.Event]] id pointing at
 *     the originating ToolInvoke) and projects each row to a worker
 *     item via the registered [[WorkerItemSourceAdapter]] for that
 *     tool's name.
 *   - [[FromFile]]    — read a file and split into items per
 *     [[ItemParser]].
 *   - [[FromConversation]] — extract worker items from a prior
 *     conversation message via the named `extractor`; apps wire
 *     conversation-extractor adapters into [[WorkerItemSourceAdapter]].
 *
 * `FromList` is fabric-RW friendly (raw `List[Json]` is one of the
 * documented JSON boundaries — workers consume opaque items, so
 * forcing a typed shape on every caller would lose the
 * "any-tool-output is dispatchable" property). The other variants
 * carry typed references (`Id[Event]`, file path string, message id)
 * and resolve through the framework's adapter registry at execute
 * time.
 */
sealed trait WorkerItemSource derives RW

object WorkerItemSource {

  /** Inline list of items. Each Json value is passed verbatim as
    * `{{item}}` substitution to the pipeline. */
  case class FromList(items: List[Json]) extends WorkerItemSource derives RW

  /** Read the persisted paginated output of a prior tool call (looked
    * up by the originating [[sigil.event.Event]] id) and project the
    * rows to worker items via the registered adapter for that tool.
    *
    *   - `callId`  — the originating `ToolInvoke` event id.
    *   - `groupBy` — optional grouping policy applied after adapter
    *                 projection. Default [[GroupBy.None]] (one item
    *                 per row).
    */
  case class FromCall(callId: Id[Event],
                      groupBy: GroupBy = GroupBy.None) extends WorkerItemSource derives RW

  /** Read a file and split into items per [[ItemParser]]. Resolved
    * via the framework's `FileSystemContext` like every other
    * filesystem-touching tool. */
  case class FromFile(filePath: String,
                      parser: ItemParser = ItemParser.Lines) extends WorkerItemSource derives RW

  /** Extract worker items from a prior conversation message via the
    * named `extractor`. Apps register conversation extractors via the
    * [[WorkerItemSourceAdapter]] registry under the
    * `conversation:<extractor>` key. */
  case class FromConversation(messageId: Id[Event],
                              extractor: String) extends WorkerItemSource derives RW
}
