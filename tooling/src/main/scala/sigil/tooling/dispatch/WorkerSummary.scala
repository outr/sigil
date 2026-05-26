package sigil.tooling.dispatch

import fabric.rw.*

/**
 * Per-worker entry on [[DispatchCompleted.workers]] — the
 * aggregated dispatch result.
 *
 *   - `itemIndex` — zero-indexed position of the item within the
 *     resolved item list.
 *   - `itemPreview` — first ~80 chars of the item payload, for
 *     forensics so the parent agent can identify which item this
 *     worker handled without re-reading the container.
 *   - `workerConversationId` — id of the worker's sub-conversation.
 *     Parent agent can query it via
 *     [[sigil.tool.util.SearchConversationTool]] etc. for
 *     drill-down forensics.
 *   - `status` — `"Success"` (worker emitted `Complete:`) or
 *     `"Failure"` (workflow status was Failure, the worker hit a
 *     terminal error before settling).
 *   - `summary` — the worker's terminator text (the `Complete: …`
 *     payload) when present. None when the worker failed before
 *     emitting a terminator.
 *   - `iterations` — how many AgentDecisionStep iterations the
 *     worker ran.
 *   - `exhausted` — true when the worker hit its `maxIterations`
 *     cap without emitting `Complete:`.
 */
case class WorkerSummary(itemIndex: Int,
                         itemPreview: String,
                         workerConversationId: String,
                         status: String,
                         summary: Option[String],
                         iterations: Int,
                         exhausted: Boolean = false) derives RW
