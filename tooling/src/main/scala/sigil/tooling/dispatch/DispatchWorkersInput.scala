package sigil.tooling.dispatch

import fabric.rw.*
import sigil.provider.Complexity
import sigil.tool.ToolInput

/**
 * Input for [[DispatchWorkersTool]]. The two-phase confirm pattern
 * matches the previous refactor family — `confirmed = false` (the
 * default) returns a scope preview, `confirmed = true` runs the
 * pipeline.
 *
 *   - `complexity`   — required routing tier handed to the framework's
 *     `ProviderStrategy` for worker model resolution.
 *   - `confirmed`    — two-phase guard. Default `false` returns
 *     [[DispatchWorkersOutput.Scope]] without running any worker;
 *     `true` runs the pipeline and returns
 *     [[DispatchWorkersOutput.Dispatched]].
 *   - `items`        — where the worker items come from
 *     ([[WorkerItemSource]]).
 *   - `pipeline`     — what each worker does ([[WorkerPipeline]]).
 *   - `workerModelId` — optional explicit model id; wins over
 *     `complexity`-routing when set.
 *   - `maxParallel`  — concurrency cap (default 5).
 *   - `maxItems`     — hard cost cap (default 10000) — refuses to
 *     dispatch more items than this.
 */
case class DispatchWorkersInput(complexity: Complexity,
                                confirmed: Boolean = false,
                                items: WorkerItemSource,
                                pipeline: WorkerPipeline,
                                workerModelId: Option[String] = None,
                                maxParallel: Int = 5,
                                maxItems: Int = 10000) extends ToolInput derives RW
