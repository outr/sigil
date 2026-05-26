package sigil.tooling.dispatch

import fabric.rw.*
import sigil.tool.ToolOutput

/**
 * Immediate handle returned when [[DispatchWorkersTool]] kicks off
 * a fanout (sigil #288). The tool returns this value as soon as
 * the first batch of workers has been scheduled — it does NOT
 * block until all workers finish.
 *
 * The aggregated per-worker results land later via a
 * [[sigil.tooling.dispatch.DispatchCompleted]] event published
 * into the parent conversation, which triggers the parent agent's
 * next iteration.
 *
 *   - `dispatchId` — opaque session id. Echoes onto every related
 *     event (DispatchStarted, DispatchCompleted) so apps can
 *     correlate the lifecycle on the wire.
 *   - `total` — total items the dispatch will process.
 *   - `workersStarted` — the initial batch size: `min(total,
 *     maxParallel)`. The remaining items are queued and dispatched
 *     as capacity frees.
 *   - `abortReason` — when set, the dispatch did NOT start
 *     (typically: items resolved to an empty container, or a
 *     cross-conversation read was refused). The handle's other
 *     fields are zero and no workers ran.
 */
case class DispatchWorkersOutput(dispatchId: String,
                                 total: Int,
                                 workersStarted: Int,
                                 abortReason: Option[String] = None) extends ToolOutput derives RW
