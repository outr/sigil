package sigil.tooling.dispatch

import fabric.rw.*
import lightdb.id.Id
import sigil.tool.ToolInput
import sigil.tool.output.ToolOutputNode

/**
 * Input for [[DispatchWorkersTool]]. The two-phase confirm pattern
 * stays — `confirmed = false` (the default) returns a scope preview,
 * `confirmed = true` runs the action over every group.
 *
 * Items always arrive through a paginated container — every tool
 * whose output is naturally a list (`grep`, `lsp_find_references`,
 * etc.) already produces one; tools that take a list of values the
 * agent assembled by reasoning compose `create_container(items)` and
 * pass the returned `itemsId`.
 *
 *   - `itemsId`    — id of the container holding the worker items.
 *     Produced by any paginated tool (grep, LSP, …) or one of the
 *     `create_container` / `load_file_as_container` / `filter_container`
 *     producer tools.
 *   - `action`     — the adhoc Scala script run once per group. Bound
 *     in scope: `items: List[fabric.Json]` (the group's payloads —
 *     length `groupSize`, except possibly smaller for the final
 *     group) and `context: sigil.TurnContext`. Same evaluator and
 *     surface as `execute_script`. The script's trailing expression
 *     is the per-group worker result. The dispatcher compiles `action`
 *     once before spawning any worker: a compile failure returns a
 *     [[DispatchWorkersOutput.CompileFailure]] and runs nothing; a
 *     successful compile is shared across every worker.
 *   - `groupSize`  — items bound into one worker invocation. Default
 *     `1` — one item per worker. Higher values batch items so the
 *     script handles batching internally. Cost preview reports
 *     `ceil(itemCount / groupSize)` worker invocations.
 *   - `itemsAt`    — optional level to read from. Default `None`
 *     reads top-level (level 0). Pass `Some(1)` to dispatch over the
 *     child nodes of a tree-shaped container.
 *   - `itemsLimit` — optional hard cap on items consumed before
 *     dispatch. Useful for "dispatch over the first N matches"
 *     without modifying the source container.
 *   - `confirmed`  — two-phase guard. Default `false` returns
 *     [[DispatchWorkersOutput.ScopePreview]] without running any
 *     worker; `true` runs the action and returns
 *     [[DispatchWorkersOutput.DispatchResult]].
 *   - `maxParallel` — concurrency cap (default 5).
 *   - `maxItems`   — hard cost cap (default 10000) — refuses to
 *     dispatch more items than this.
 */
case class DispatchWorkersInput(itemsId: Id[ToolOutputNode],
                                action: String,
                                groupSize: Int = 1,
                                confirmed: Boolean = false,
                                itemsAt: Option[Int] = None,
                                itemsLimit: Option[Int] = None,
                                maxParallel: Int = 5,
                                maxItems: Int = 10000) extends ToolInput derives RW
