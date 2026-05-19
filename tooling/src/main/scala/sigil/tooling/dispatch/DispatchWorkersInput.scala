package sigil.tooling.dispatch

import fabric.rw.*
import lightdb.id.Id
import sigil.provider.Complexity
import sigil.tool.ToolInput
import sigil.tool.output.ToolOutputNode

/**
 * Input for [[DispatchWorkersTool]]. The two-phase confirm pattern
 * stays — `confirmed = false` (the default) returns a scope
 * preview, `confirmed = true` runs the pipeline.
 *
 * Items always arrive through a paginated container — every other
 * tool whose output is naturally a list (`grep`, `lsp_find_references`,
 * etc.) already produces one; tools that take a list of values
 * the agent assembled by reasoning compose
 * `create_container(items)` → use the returned `itemsId`.
 *
 *   - `complexity`   — required routing tier handed to the framework's
 *     `ProviderStrategy` for worker model resolution.
 *   - `itemsId`      — id of the container holding the worker items.
 *     Produced by any paginated tool (grep, LSP, …) or one of the
 *     `create_container` / `load_file_as_container` / `filter_container`
 *     producer tools.
 *   - `itemsAt`      — optional level to read from. Default `None`
 *     reads top-level (level 0). Pass `Some(1)` to dispatch one
 *     worker per child node of a tree-shaped container (e.g.
 *     per-line matches under each grep file).
 *   - `itemsLimit`   — optional hard cap on items consumed before
 *     dispatch. Useful for "dispatch over the first N matches"
 *     without modifying the source container.
 *   - `confirmed`    — two-phase guard. Default `false` returns
 *     [[DispatchWorkersOutput.ScopePreview]] without running any
 *     worker; `true` runs the pipeline and returns
 *     [[DispatchWorkersOutput.DispatchResult]].
 *   - `pipeline`     — what each worker does ([[WorkerPipeline]]).
 *   - `workerModelId` — optional explicit model id; wins over
 *     `complexity`-routing when set.
 *   - `maxParallel`  — concurrency cap (default 5).
 *   - `maxItems`     — hard cost cap (default 10000) — refuses to
 *     dispatch more items than this.
 */
case class DispatchWorkersInput(complexity: Complexity,
                                itemsId: Id[ToolOutputNode],
                                pipeline: WorkerPipeline,
                                confirmed: Boolean = false,
                                itemsAt: Option[Int] = None,
                                itemsLimit: Option[Int] = None,
                                workerModelId: Option[String] = None,
                                maxParallel: Int = 5,
                                maxItems: Int = 10000) extends ToolInput derives RW
