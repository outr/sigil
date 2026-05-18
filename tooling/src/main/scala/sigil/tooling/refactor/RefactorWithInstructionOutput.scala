package sigil.tooling.refactor

import fabric.rw.*
import lightdb.id.Id
import sigil.event.Event

/**
 * Output for the prepare step of [[RefactorWithInstructionTool]].
 *
 * Carries the session handle (`sessionId`) the agent passes back
 * to the apply / cancel tools, plus a first-page slice of the
 * per-file diffs the workers produced. Subsequent pages are
 * reachable via the standard pagination tools
 * (`next_page(referenceId = sessionId)` / `query_tool_output`)
 * because the prepare step drains every per-file diff into
 * `db.toolOutputs` keyed by `callId == sessionId`.
 *
 *   - `sessionId`     — opaque handle for the follow-up tools and
 *     the pagination read-side. Equal to the originating
 *     [[sigil.event.ToolInvoke]] id.
 *   - `totalDiffs`    — total per-file diff rows drained.
 *   - `filesAffected` — distinct file paths with at least one
 *     committable edit.
 *   - `page0Diffs`    — first window of [[FileRefactorReport]]s
 *     inline.
 *   - `hasMore` / `nodeIds` / `callId` / `referenceId` /
 *     `pageSize`    — mirror the [[sigil.tool.output.JsonPagedResult]]
 *     contract so client UIs treat the prepare result the same way
 *     they treat any paginated tool output.
 *   - `perFileSummary` — path → match count, for cheap
 *     "which files changed and by how much" rendering without
 *     fetching every diff page.
 *   - `abortReason`   — surface-level abort (e.g. `maxWorkers`
 *     exceeded). When set, no session was created and no further
 *     tools should be called.
 */
case class RefactorWithInstructionOutput(sessionId: String,
                                         totalDiffs: Int,
                                         filesAffected: Int,
                                         page0Diffs: List[FileRefactorReport],
                                         hasMore: Boolean,
                                         nodeIds: List[String],
                                         callId: Id[Event],
                                         referenceId: String,
                                         pageSize: Int,
                                         perFileSummary: Map[String, Int],
                                         abortReason: Option[String] = None)
  derives RW

/**
 * Per-file outcome: the worker's decisions + the resulting unified
 * diff. Drained one row per file into `db.toolOutputs` so the agent
 * pages through them via the standard pagination tools.
 */
case class FileRefactorReport(path: String,
                              workerDecisions: List[MatchDecision],
                              workerError: Option[String] = None,
                              appliedDiff: Option[String] = None)
  derives RW
