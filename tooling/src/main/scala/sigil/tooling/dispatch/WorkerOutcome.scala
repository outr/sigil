package sigil.tooling.dispatch

import fabric.Json
import fabric.rw.*

/**
 * Per-group outcome of one [[DispatchWorkersTool]] worker invocation.
 *
 *   - `itemIndex` — 0-based index of the group within the dispatch.
 *     With the default `groupSize = 1` this is the item's own index;
 *     with a larger group size it is the group ordinal.
 *   - `result`    — `Left(message)` when the action script threw at
 *     runtime for this group (other groups are unaffected),
 *     `Right(json)` carrying the script's return value on success.
 */
case class WorkerOutcome(itemIndex: Int, result: Either[String, Json]) derives RW
