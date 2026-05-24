package sigil.tooling.dispatch

import fabric.Json
import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.script.{CompiledScript, ScriptBinding, ScriptExecutor}
import sigil.tool.output.ToolOutputNode
import sigil.tool.{Tool, ToolExample, ToolName}
import sigil.tooling.container.ContainerSupport

/**
 * Generic parallel per-item action over a container of items.
 *
 * The agent supplies one adhoc Scala `action` script. The dispatcher
 * compiles it ONCE, then runs the compiled artifact in parallel — one
 * worker per group of `groupSize` items. The script's per-group
 * binding is `items: List[fabric.Json]` (the group's payloads) plus
 * `context: sigil.tool.ToolContext`; its trailing expression is the
 * per-group worker result.
 *
 * Items come from a container: any tool whose output is paginated
 * (grep, LSP, …) materialises rows in `db.toolOutputs` keyed by a
 * `callId`. Pass that callId — or the `itemsId` returned by
 * `create_container` / `load_file_as_container` / `filter_container`
 * — as `itemsId`. The dispatcher reads top-level rows by default (or
 * `itemsAt`'s nominated level) and caps at `itemsLimit` if set.
 *
 * Two-phase confirm: `confirmed = false` (default) returns a
 * [[DispatchWorkersOutput.ScopePreview]] after a successful compile —
 * no worker runs; `confirmed = true` runs the action over every group
 * and returns [[DispatchWorkersOutput.DispatchResult]]. A pre-flight
 * compile failure short-circuits both phases with
 * [[DispatchWorkersOutput.CompileFailure]].
 *
 * The `action` script runs against the host's `ScriptExecutor` — the
 * injected one when supplied, otherwise the host Sigil's (when it
 * mixes in `ScriptSigil`). When no executor is available the tool
 * surfaces a structured abort.
 */
final class DispatchWorkersTool(scriptExecutor: Option[ScriptExecutor] = None) extends Tool
  with sigil.tool.DestructiveExternalTool {
  type Input  = DispatchWorkersInput
  type Output = DispatchWorkersOutput
  val inputRW  = summon[RW[DispatchWorkersInput]]
  val outputRW = summon[RW[DispatchWorkersOutput]]

  val name = ToolName("dispatch_workers")
  val description =
    """Run an adhoc Scala `action` script over a container of items in parallel. The dispatcher
      |compiles the action once, then runs it as N parallel workers. Composable with grep / LSP /
      |any tool whose output is a paginated list, plus inline / file / filter containers via the
      |producer tools.
      |
      |Two-phase confirm. First call with `confirmed = false` (the default): the action is
      |compiled and a ScopePreview is returned without dispatching any worker. If the action does
      |not compile, a CompileFailure with typed line / column / message errors is returned and no
      |worker runs. Read the preview's `confirmCall` directive; re-invoke with `confirmed = true`
      |to run.
      |
      |The `action` script is Scala 3, same evaluator and surface as `execute_script`. Per worker
      |it has `items: List[Json]` (the group's item payloads — one element with the default
      |groupSize=1) and `context: ToolContext` in scope. The script's trailing expression is the
      |per-group worker result. Runtime errors in one worker are isolated — other workers still
      |run.
      |
      |Items come from any container. Producers:
      |  - Any paginated tool (grep, lsp_find_references, lsp_workspace_symbols, ...) — pass its
      |    `callId` as `itemsId`.
      |  - `create_container([items])` — assemble a container from an inline list.
      |  - `load_file_as_container(path, parser)` — read a file into a container.
      |  - `filter_container(sourceId, predicate)` — narrow an existing container.
      |
      |Required: `itemsId` (container reference), `action` (the per-group script). `groupSize`
      |(default 1) batches items into one worker invocation — the action handles batching
      |internally. `itemsAt` (default 0) picks the tree level to dispatch over. `itemsLimit` caps
      |the count consumed without modifying the source. `maxParallel` (default 5) caps concurrent
      |worker invocations; `maxItems` (default 10000) caps the total item count to avoid runaway
      |work.""".stripMargin
  override val keywords = Set(
    "dispatch", "workers", "parallel", "per-item", "per item",
    "refactor", "rewrite", "modify", "multi-file", "across files", "worker",
    "per-match", "regex", "code change", "edit", "transform",
    "find", "replace", "find and replace", "search and replace",
    "search and edit", "find and edit", "bulk edit", "bulk replace",
    "rewrite across files", "remove", "delete pattern", "substitute",
    "search", "match", "classify", "annotate", "extract", "batch",
    "loop", "map", "fan out", "action", "script"
  )
  override val examples = List(
    ToolExample(
      "Preview dispatching an action over a grep result (confirmed=false)",
      DispatchWorkersInput(
        itemsId = lightdb.id.Id[ToolOutputNode]("example-grep-call-id"),
        action  = "items.head"
      )
    ),
    ToolExample(
      "Uppercase a field of each item (confirmed=true)",
      DispatchWorkersInput(
        itemsId   = lightdb.id.Id[ToolOutputNode]("example-list-container-id"),
        action    = "fabric.Str(items.head(\"name\").asString.toUpperCase)",
        confirmed = true
      )
    )
  )


  override def executeOutput(input: DispatchWorkersInput,
                             ctx: ToolContext): Task[DispatchWorkersOutput] = {
    if (input.groupSize < 1) {
      return Task.pure(DispatchWorkersOutput.DispatchResult(
        sessionId    = rapid.Unique(),
        totalItems   = 0,
        successCount = 0,
        failureCount = 0,
        perItem      = Nil,
        abortReason  = Some(s"dispatch_workers: groupSize must be >= 1 (got ${input.groupSize}).")
      ))
    }
    val executor: Option[ScriptExecutor] = scriptExecutor.orElse(DispatchWorkersTool.scriptExecutorOf(ctx.sigil))
    executor match {
      case None =>
        Task.pure(DispatchWorkersOutput.DispatchResult(
          sessionId    = rapid.Unique(),
          totalItems   = 0,
          successCount = 0,
          failureCount = 0,
          perItem      = Nil,
          abortReason  = Some(
            "dispatch_workers: no ScriptExecutor available — the host Sigil must mix in ScriptSigil " +
              "(or an executor must be injected) to compile and run the action script."
          )
        ))
      case Some(exec) =>
        ContainerSupport.resolveItems(ctx, input.itemsId, input.itemsAt, input.itemsLimit).flatMap { items =>
          dispatch(input, ctx, exec, items)
        }
    }
  }

  // ---- core flow ----

  private def dispatch(input: DispatchWorkersInput,
                       ctx: ToolContext,
                       exec: ScriptExecutor,
                       items: List[Json]): Task[DispatchWorkersOutput] = {
    val totalItems = items.size
    val capExceeded = totalItems > input.maxItems
    // Compile `action` once — before any worker, before the scope
    // preview. A compile failure short-circuits both phases.
    exec.compile(input.action, DispatchWorkersTool.ActionBindings).flatMap {
      case Left(errors) =>
        Task.pure(DispatchWorkersOutput.CompileFailure(errors))
      case Right(compiled) =>
        if (!input.confirmed) {
          val abortReason: Option[String] =
            if (capExceeded)
              Some(s"found $totalItems items; refusing to dispatch more than maxItems=${input.maxItems}. " +
                "Narrow the item source (e.g. filter_container) or raise the cap, then re-preview.")
            else None
          Task.pure(DispatchWorkersOutput.ScopePreview(
            sessionId     = rapid.Unique(),
            totalItems    = totalItems,
            workerCount   = workerCount(totalItems, input.groupSize),
            actionPreview = DispatchWorkersTool.preview(input.action),
            compileOk     = true,
            perItemSample = items.take(DispatchWorkersTool.PreviewSampleSize),
            confirmCall   = renderConfirmCall(workerCount(totalItems, input.groupSize)),
            abortReason   = abortReason
          ))
        } else if (items.isEmpty) {
          Task.pure(DispatchWorkersOutput.DispatchResult(
            sessionId    = rapid.Unique(),
            totalItems   = 0,
            successCount = 0,
            failureCount = 0,
            perItem      = Nil,
            abortReason  = Some(
              "dispatch_workers received an empty items list with confirmed=true. " +
                "There's nothing to dispatch. Verify the itemsId resolves to a container with items — " +
                "create_container, load_file_as_container, filter_container, or any paginated tool's callId."
            )
          ))
        } else if (capExceeded) {
          Task.pure(DispatchWorkersOutput.DispatchResult(
            sessionId    = rapid.Unique(),
            totalItems   = totalItems,
            successCount = 0,
            failureCount = 0,
            perItem      = Nil,
            abortReason  = Some(s"found $totalItems items; refusing to dispatch more than maxItems=${input.maxItems}.")
          ))
        } else {
          runWorkers(input, ctx, compiled, items)
        }
    }
  }

  private def runWorkers(input: DispatchWorkersInput,
                         ctx: ToolContext,
                         compiled: CompiledScript,
                         items: List[Json]): Task[DispatchWorkersOutput] = {
    val groups: List[List[Json]] = items.grouped(input.groupSize).toList
    val perGroup: List[Task[WorkerOutcome]] = groups.zipWithIndex.map { case (group, index) =>
      val bindings: Map[String, Any] = Map(
        "items"   -> group,
        "context" -> ctx
      )
      compiled.invoke(bindings)
        .map(result => WorkerOutcome(index, result))
        .handleError { t =>
          val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
          Task.pure(WorkerOutcome(index, Left(msg)))
        }
    }
    Task.parSequenceBounded(perGroup, parallelism = math.max(1, input.maxParallel)).map { outcomes =>
      val successCount = outcomes.count(_.result.isRight)
      val failureCount = outcomes.size - successCount
      DispatchWorkersOutput.DispatchResult(
        sessionId    = rapid.Unique(),
        totalItems   = items.size,
        successCount = successCount,
        failureCount = failureCount,
        perItem      = outcomes
      )
    }
  }

  // ---- helpers ----

  private def workerCount(totalItems: Int, groupSize: Int): Int =
    if (totalItems <= 0) 0
    else math.ceil(totalItems.toDouble / math.max(1, groupSize)).toInt

  private def renderConfirmCall(workers: Int): String =
    s"call dispatch_workers again with the same arguments and confirmed=true to dispatch $workers " +
      "workers running the compiled action script"
}

object DispatchWorkersTool {

  /** Hard cap on `perItemSample` in [[DispatchWorkersOutput.ScopePreview]].
    * Keeps the preview body comfortably under the framework's inline-
    * truncation threshold even for 1000+ item containers. */
  val PreviewSampleSize: Int = 10

  /** Character cap on `actionPreview` in
    * [[DispatchWorkersOutput.ScopePreview]]. */
  val ActionPreviewLength: Int = 200

  /** The per-worker binding surface the `action` script compiles
    * against — the group's item payloads and the turn context. */
  val ActionBindings: List[ScriptBinding] = List(
    ScriptBinding("items", "List[fabric.Json]"),
    ScriptBinding("context", "sigil.tool.ToolContext")
  )

  /** First [[ActionPreviewLength]] characters of `action`, for the
    * scope preview's sanity-check field. */
  private[dispatch] def preview(action: String): String =
    if (action.length <= ActionPreviewLength) action
    else action.take(ActionPreviewLength)

  /** Reflective discovery of an `Option[ScriptExecutor]` on the host
    * Sigil. Apps mixing in `ScriptSigil` expose `scriptExecutor:
    * ScriptExecutor`; we use reflection because `tooling/` cannot
    * statically depend on `script/`'s opt-in mixin (that would force
    * every tooling user to pull in scala3-repl). When the host
    * doesn't have the method, returns `None` and the dispatcher
    * surfaces a structured abort. */
  private[dispatch] def scriptExecutorOf(host: AnyRef): Option[ScriptExecutor] = {
    val cls = host.getClass
    val method = scala.util.Try(cls.getMethod("scriptExecutor")).toOption
    method.flatMap { m =>
      scala.util.Try(m.invoke(host)) match {
        case scala.util.Success(value: ScriptExecutor) => Some(value)
        case _                                         => None
      }
    }
  }
}
