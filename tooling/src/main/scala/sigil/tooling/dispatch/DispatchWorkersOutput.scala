package sigil.tooling.dispatch

import fabric.Json
import fabric.rw.*
import sigil.script.CompileError

/**
 * Sum of the three shapes [[DispatchWorkersTool]] returns —
 *
 *  - [[CompileFailure]] — pre-flight compilation of the `action`
 *    script failed; no worker ran. Carries typed [[CompileError]]s.
 *  - [[ScopePreview]]   — the `confirmed = false` reply: item count,
 *    a preview of the (successfully compiled) action, and a
 *    `confirmCall` directive. No worker ran.
 *  - [[DispatchResult]] — the `confirmed = true` reply: one
 *    [[WorkerOutcome]] per worker group.
 */
sealed trait DispatchWorkersOutput derives RW

object DispatchWorkersOutput {

  /** Returned when pre-flight compilation of the `action` script
    * fails. No worker is dispatched — the agent fixes the script from
    * the typed errors and retries.
    *
    *   - `errors` — one [[CompileError]] per compiler diagnostic, with
    *     line / column / message. Never empty when this variant is
    *     returned. */
  case class CompileFailure(errors: List[CompileError]) extends DispatchWorkersOutput derives RW

  /** Scope-preview returned when `confirmed = false`. The `action`
    * compiled cleanly; no worker ran. The agent reviews and re-invokes
    * with `confirmed = true`.
    *
    * Designed to fit well under the framework's inline truncation
    * threshold (8192 bytes) — the per-item sample is capped at 10
    * items.
    *
    *   - `sessionId`     — opaque identifier for the preview
    *     round-trip, echoed so wire-log readers can correlate the
    *     preview with its subsequent `confirmed = true` re-issue.
    *   - `totalItems`    — total worker items after `itemsAt` /
    *     `itemsLimit` resolution.
    *   - `workerCount`   — number of worker invocations a
    *     `confirmed = true` run would dispatch: `ceil(totalItems /
    *     groupSize)`.
    *   - `actionPreview` — the first ~200 characters of the action
    *     script, for a sanity-check of what will run.
    *   - `compileOk`     — always `true` here; present for symmetry
    *     with [[CompileFailure]].
    *   - `perItemSample` — first 10 items so the agent can
    *     sanity-check the dispatch shape before confirming.
    *   - `confirmCall`   — exact human-readable instruction the agent
    *     reads to re-invoke.
    *   - `abortReason`   — surface-level abort (e.g. `maxItems`
    *     exceeded). When set, no dispatch should follow. */
  case class ScopePreview(sessionId: String,
                          totalItems: Int,
                          workerCount: Int,
                          actionPreview: String,
                          compileOk: Boolean,
                          perItemSample: List[Json],
                          confirmCall: String,
                          abortReason: Option[String] = None) extends DispatchWorkersOutput derives RW

  /** Actual dispatch result returned when `confirmed = true`. Carries
    * one [[WorkerOutcome]] per worker group, in input order.
    *
    *   - `sessionId`    — opaque dispatch identifier.
    *   - `totalItems`   — total worker items dispatched over.
    *   - `successCount` — groups whose action returned a value.
    *   - `failureCount` — groups whose action threw at runtime.
    *   - `perItem`      — per-group outcomes in input order.
    *   - `abortReason`  — surface-level abort (e.g. empty container,
    *     `maxItems` exceeded). When set, no worker ran. */
  case class DispatchResult(sessionId: String,
                            totalItems: Int,
                            successCount: Int,
                            failureCount: Int,
                            perItem: List[WorkerOutcome],
                            abortReason: Option[String] = None) extends DispatchWorkersOutput derives RW
}
