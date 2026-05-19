package sigil.tooling.dispatch

import fabric.Json
import fabric.rw.*

/**
 * Sum of the two shapes [[DispatchWorkersTool]] returns —
 *
 *  - [[ScopePreview]]    — the `confirmed = false` reply: total
 *    item count, estimated worker call count, resolved model id,
 *    and a `confirmCall` directive the agent reads to re-issue
 *    with `confirmed = true`. No worker LLM calls run.
 *  - [[DispatchResult]]  — the `confirmed = true` reply: per-item
 *    [[WorkerResult]] list and aggregate counts.
 */
sealed trait DispatchWorkersOutput derives RW

object DispatchWorkersOutput {

  /** Scope-preview returned when `confirmed = false`. Resolves the
    * worker item count + worker model id without dispatching any
    * worker. The agent reviews and re-invokes with `confirmed = true`.
    *
    * Designed to fit well under the framework's inline truncation
    * threshold (8192 bytes) — the per-item sample is capped at 10
    * items.
    *
    *   - `sessionId`                — opaque identifier for the
    *     preview round-trip. Echoed back to the agent so wire-log
    *     readers can correlate the preview with its subsequent
    *     `confirmed = true` re-issue.
    *   - `totalItems`               — total worker items after
    *     `itemsAt` / `itemsLimit` resolution.
    *   - `estimatedWorkerCallCount` — number of worker calls that
    *     `confirmed = true` would dispatch. Same as `totalItems`
    *     today (one worker per item); the separate field
    *     accommodates future grouping shapes.
    *   - `resolvedModelId`          — worker model id resolved via
    *     `ProviderStrategy` at the input's `complexity`. Empty
    *     when no strategy / candidate resolved.
    *   - `estimatedCostNote`        — short human-readable cost
    *     hint.
    *   - `perItemSample`            — first 10 items so the agent
    *     can sanity-check the dispatch shape before confirming.
    *   - `confirmCall`              — exact human-readable
    *     instruction the agent reads to re-invoke.
    *   - `abortReason`              — surface-level abort (e.g.
    *     `maxItems` exceeded). When set, no dispatch should follow.
    */
  case class ScopePreview(sessionId: String,
                          totalItems: Int,
                          estimatedWorkerCallCount: Int,
                          resolvedModelId: String,
                          estimatedCostNote: String,
                          perItemSample: List[Json],
                          confirmCall: String,
                          abortReason: Option[String] = None) extends DispatchWorkersOutput derives RW

  /** Actual dispatch result returned when `confirmed = true`.
    * Carries the per-item [[WorkerResult]] list — one entry per
    * worker invocation, in input order. */
  case class DispatchResult(totalItems: Int,
                            successCount: Int,
                            failureCount: Int,
                            results: List[WorkerResult],
                            resolvedModelId: String,
                            abortReason: Option[String] = None) extends DispatchWorkersOutput derives RW
}
