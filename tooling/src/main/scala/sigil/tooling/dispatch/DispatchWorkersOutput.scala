package sigil.tooling.dispatch

import fabric.Json
import fabric.rw.*

/**
 * Sum of the two shapes [[DispatchWorkersTool]] returns — the
 * [[DispatchWorkersOutput.Scope]] preview when `confirmed = false`
 * and the [[DispatchWorkersOutput.Dispatched]] artefact once the
 * pipeline has run.
 */
sealed trait DispatchWorkersOutput derives RW

object DispatchWorkersOutput {

  /** Scope preview returned when `confirmed = false`. Resolves the
    * worker item count + worker model id without dispatching any
    * worker. The agent reviews and re-invokes with `confirmed = true`.
    *
    *   - `items`             — preview window of the resolved items
    *     (capped — `totalItems` carries the full count).
    *   - `totalItems`        — total worker items after item-source
    *     resolution.
    *   - `resolvedModelId`   — worker model id resolved via
    *     [[sigil.provider.ProviderStrategy]] at the input's
    *     `complexity` for `CodingWork`. Empty when no strategy /
    *     candidate resolved.
    *   - `estimatedCostNote` — short human-readable cost hint.
    *   - `confirmCall`       — exact wire shape for the confirm
    *     re-invocation.
    *   - `abortReason`       — surface-level abort (e.g. `maxItems`
    *     exceeded). When set, no dispatch should follow.
    */
  case class Scope(items: List[Json],
                   totalItems: Int,
                   resolvedModelId: String,
                   estimatedCostNote: String,
                   confirmCall: String,
                   abortReason: Option[String] = None) extends DispatchWorkersOutput derives RW

  /** Dispatched output returned when `confirmed = true`. Carries the
    * per-item [[WorkerResult]] list — one entry per worker
    * invocation, in input order. */
  case class Dispatched(totalItems: Int,
                        successCount: Int,
                        failureCount: Int,
                        results: List[WorkerResult],
                        resolvedModelId: String,
                        abortReason: Option[String] = None) extends DispatchWorkersOutput derives RW
}
