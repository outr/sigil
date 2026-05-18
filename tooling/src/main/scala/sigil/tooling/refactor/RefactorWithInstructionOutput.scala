package sigil.tooling.refactor

import fabric.rw.*
import lightdb.id.Id
import sigil.event.Event

/**
 * Sum of the two shapes [[RefactorWithInstructionTool]] returns —
 * the [[RefactorWithInstructionScope]] preview when `confirmed =
 * false` and the [[RefactorWithInstructionDispatched]] artefact
 * once workers have run. Callers pattern-match on the variant; the
 * two shapes are intentionally disjoint because the data the
 * agent acts on is disjoint (preview → "is this scope what I
 * wanted?", dispatched → "review the staged diffs and commit").
 */
sealed trait RefactorWithInstructionOutput derives RW

object RefactorWithInstructionOutput {

  /**
   * Scope preview returned when `confirmed = false`. Carries
   * enough detail for the agent (or user, via the agent) to
   * decide whether the resolved file set + match counts justify
   * the dispatch cost — and the resolved worker model id so the
   * cost-by-tier choice is visible.
   *
   *   - `sessionId`        — opaque id minted for this preview.
   *     Not required to re-confirm; the framework re-greps from
   *     the same input parameters. Echoed so client UIs can
   *     correlate the preview with the dispatched session that
   *     follows.
   *   - `totalFiles`       — distinct candidate files (every one
   *     becomes a paid worker call on confirm).
   *   - `totalMatches`     — total grep hits across every file.
   *   - `perFileMatchCounts` — `path → match count` for the top
   *     candidates (truncated to a sensible window when the
   *     match set is huge, see `perFileMatchCountsTruncated`).
   *   - `perFileMatchCountsTruncated` — true when
   *     `perFileMatchCounts` doesn't cover every file; the full
   *     set is too large to inline.
   *   - `resolvedModelId`  — the worker model id the framework
   *     would dispatch with, resolved from `complexity` against
   *     the chain's `ProviderStrategy`. Empty when no strategy /
   *     candidate resolved; the agent can read this and fall
   *     back to its own choice.
   *   - `estimatedWorkerCallCount` — number of worker LLM calls
   *     the confirm would dispatch. One per file today.
   *   - `estimatedCostNote` — short human-readable cost hint
   *     based on the resolved complexity tier; no dollars,
   *     because the framework doesn't ship a price table.
   *   - `confirmCall`      — exact wire shape to call back with
   *     `confirmed = true`. The string is documentation for the
   *     agent's next move, not a structured payload.
   *   - `abortReason`      — surface-level abort (e.g. `maxFiles`
   *     exceeded). When set, no dispatch should follow.
   */
  case class Scope(sessionId: String,
                   totalFiles: Int,
                   totalMatches: Int,
                   perFileMatchCounts: Map[String, Int],
                   perFileMatchCountsTruncated: Boolean,
                   resolvedModelId: String,
                   estimatedWorkerCallCount: Int,
                   estimatedCostNote: String,
                   confirmCall: String,
                   abortReason: Option[String] = None) extends RefactorWithInstructionOutput derives RW

  /**
   * Dispatched output returned when `confirmed = true`. Carries
   * the session handle the apply / cancel tools reference, plus a
   * first-page slice of the per-file diffs the workers produced.
   * Subsequent pages are reachable via the standard pagination
   * tools (`next_page(referenceId = sessionId)` / `query_tool_output`)
   * because the prepare step drains every per-file diff into
   * `db.toolOutputs` keyed by `callId == sessionId`.
   *
   *   - `sessionId`     — opaque handle for the follow-up tools
   *     and the pagination read-side. Equal to the originating
   *     [[sigil.event.ToolInvoke]] id.
   *   - `totalDiffs`    — total per-file diff rows drained.
   *   - `filesAffected` — distinct file paths with at least one
   *     committable edit.
   *   - `page0Diffs`    — first window of [[FileRefactorReport]]s
   *     inline.
   *   - `hasMore` / `nodeIds` / `callId` / `referenceId` /
   *     `pageSize`    — mirror the [[sigil.tool.output.JsonPagedResult]]
   *     contract so client UIs treat the dispatched result the
   *     same way they treat any paginated tool output.
   *   - `perFileSummary` — path → match count, for cheap
   *     "which files changed and by how much" rendering without
   *     fetching every diff page.
   *   - `abortReason`   — surface-level abort detected during
   *     dispatch. When set, no session was created and no further
   *     tools should be called.
   */
  case class Dispatched(sessionId: String,
                        totalDiffs: Int,
                        filesAffected: Int,
                        page0Diffs: List[FileRefactorReport],
                        hasMore: Boolean,
                        nodeIds: List[String],
                        callId: Id[Event],
                        referenceId: String,
                        pageSize: Int,
                        perFileSummary: Map[String, Int],
                        abortReason: Option[String] = None) extends RefactorWithInstructionOutput derives RW
}
