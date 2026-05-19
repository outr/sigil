package sigil.tooling.dispatch

import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import fabric.{Arr, Bool, Json, NumDec, NumInt, Obj, Str}
import rapid.Task
import sigil.TurnContext
import sigil.provider.{CodingWork, Complexity, GenerationSettings, OneShotRequest, ProviderEvent, ProviderStrategy}
import sigil.script.ScriptExecutor
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}
import sigil.tool.output.ToolOutputNode
import sigil.tooling.container.ContainerSupport

/**
 * Generic parallel LLM-and-script per-item pipeline.
 *
 * Pipeline shapes:
 *   - LLM-only — classify / annotate / categorise each item.
 *   - LLM + script — propose an edit per item, apply via the
 *     script step (e.g. `edit_file` with its safe-edit
 *     `expectedHash` flow).
 *   - Script-only — deterministic per-item transformation, no
 *     model cost.
 *
 * Items come from a container: any tool whose output is paginated
 * (grep, LSP, …) materialises rows in `db.toolOutputs` keyed by a
 * `callId`. Pass that callId — or the `itemsId` returned by
 * `create_container` / `load_file_as_container` / `filter_container`
 * — as `itemsId`. The dispatcher reads top-level rows by default
 * (or `itemsAt`'s nominated level), caps at `itemsLimit` if set,
 * and dispatches one worker per item.
 *
 * Two-phase confirm mirrors the prior refactor flow: `confirmed =
 * false` (default) returns a [[DispatchWorkersOutput.ScopePreview]]
 * with the resolved worker count + worker model id + a
 * `confirmCall` directive; `confirmed = true` runs the pipeline
 * and returns [[DispatchWorkersOutput.DispatchResult]].
 *
 * `ScriptStep` requires the host Sigil to mix in `ScriptSigil`. When
 * the host doesn't, items requesting script execution surface a
 * structured [[WorkerResult.Skipped]] outcome.
 */
final class DispatchWorkersTool(scriptExecutor: Option[ScriptExecutor] = None)
  extends TypedOutputTool[DispatchWorkersInput, DispatchWorkersOutput](
    name = ToolName("dispatch_workers"),
    description =
      """Run a per-item pipeline (optional LLM step + optional script step) over a container of
        |items in parallel. Composable with grep / LSP / any tool whose output is a paginated list,
        |plus inline / file / filter containers via the producer tools.
        |
        |Two-phase confirm. First call with `confirmed = false` (the default): resolves the
        |worker-item count and worker model id and returns a ScopePreview without dispatching any
        |worker. Read the preview's `confirmCall` directive; re-invoke with `confirmed = true` to
        |run.
        |
        |Pipeline shapes:
        |  - LLM-only: classify / annotate each item — set `pipeline.llm`, leave `pipeline.script`
        |    unset. Free-form text response by default; pass `pipeline.llm.outputSchema` to constrain
        |    the response to a typed JSON shape.
        |  - LLM + script: model proposes a value per item, script applies it (e.g. a safe-edit file
        |    write with hash-based optimistic concurrency). Set both `pipeline.llm` and
        |    `pipeline.script`.
        |  - Script-only: deterministic per-item computation, no model cost. Set only `pipeline.script`.
        |
        |Items come from any container. Producers:
        |  - Any paginated tool (grep, lsp_find_references, lsp_workspace_symbols, ...) — pass its
        |    `callId` as `itemsId`.
        |  - `create_container([items])` — assemble a container from an inline list.
        |  - `load_file_as_container(path, parser)` — read a file into a container.
        |  - `filter_container(sourceId, predicate)` — narrow an existing container.
        |
        |Required: `complexity` (routing tier for the worker model), `itemsId` (container reference).
        |`workerModelId` overrides routing. `itemsAt` (default 0) picks the tree level to dispatch
        |over (e.g. set to 1 for per-line grep matches under each file). `itemsLimit` caps the count
        |consumed without modifying the source. `maxParallel` (default 5) caps concurrent worker
        |invocations; `maxItems` (default 10000) caps the total item count to avoid runaway
        |billing.""".stripMargin,
    keywords = Set(
      "dispatch", "workers", "parallel", "pipeline", "per-item", "per item",
      "refactor", "rewrite", "modify", "multi-file", "across files", "worker",
      "judgment", "per-match", "regex", "code change", "edit", "transform",
      "find", "replace", "find and replace", "search and replace",
      "search and edit", "find and edit", "bulk edit", "bulk replace",
      "rewrite across files", "remove", "delete pattern", "substitute",
      "search", "match", "classify", "annotate", "extract", "batch",
      "loop", "map", "fan out"
    ),
    examples = List(
      ToolExample(
        "Preview dispatching workers over a grep result (confirmed=false)",
        DispatchWorkersInput(
          complexity = Complexity.Low,
          itemsId    = lightdb.id.Id[ToolOutputNode]("example-grep-call-id"),
          pipeline   = WorkerPipeline(
            llm = Some(LlmStep(
              prompt = "Remove every `// Bug #NNN` comment fragment from this file. File: {{item.filePath}}."
            ))
          )
        )
      ),
      ToolExample(
        "Classify each item with an LLM, return free-form text (confirmed=true)",
        DispatchWorkersInput(
          complexity = Complexity.Low,
          confirmed  = true,
          itemsId    = lightdb.id.Id[ToolOutputNode]("example-list-container-id"),
          pipeline   = WorkerPipeline(
            llm = Some(LlmStep(prompt = "Classify the sentiment of: {{item}}"))
          )
        )
      )
    )
  ) with sigil.tool.DestructiveExternalTool {

  override def paginate: Boolean = false

  override protected def executeTyped(input: DispatchWorkersInput,
                                      ctx: TurnContext): Task[DispatchWorkersOutput] = {
    if (input.pipeline.llm.isEmpty && input.pipeline.script.isEmpty) {
      return Task.pure(DispatchWorkersOutput.DispatchResult(
        totalItems      = 0,
        successCount    = 0,
        failureCount    = 0,
        results         = Nil,
        resolvedModelId = "",
        abortReason     = Some("dispatch_workers: pipeline must declare at least one of `llm` or `script` — empty pipelines have nothing to run.")
      ))
    }
    ContainerSupport.resolveItems(ctx, input.itemsId, input.itemsAt, input.itemsLimit).flatMap { items =>
      val totalItems = items.size
      val capExceeded = totalItems > input.maxItems
      if (items.isEmpty && input.confirmed) {
        Task.pure(DispatchWorkersOutput.DispatchResult(
          totalItems      = 0,
          successCount    = 0,
          failureCount    = 0,
          results         = Nil,
          resolvedModelId = "",
          abortReason     = Some(
            "dispatch_workers received an empty items list with confirmed=true. " +
              "There's nothing to dispatch. Verify the itemsId resolves to a container with items — " +
              "create_container, load_file_as_container, filter_container, or any paginated tool's callId."
          )
        ))
      } else resolveWorkerModelId(input, ctx).flatMap { resolvedModelId =>
        if (!input.confirmed) {
          val abortReason: Option[String] =
            if (capExceeded)
              Some(s"found $totalItems items; refusing to dispatch more than maxItems=${input.maxItems}. " +
                "Narrow the item source (e.g. filter_container) or raise the cap, then re-preview.")
            else None
          val sample = items.take(DispatchWorkersTool.PreviewSampleSize)
          val sessionId = rapid.Unique()
          Task.pure(DispatchWorkersOutput.ScopePreview(
            sessionId                = sessionId,
            totalItems               = totalItems,
            estimatedWorkerCallCount = totalItems,
            resolvedModelId          = resolvedModelId,
            estimatedCostNote        = costNote(input.complexity, totalItems, input.pipeline),
            perItemSample            = sample,
            confirmCall              = renderConfirmCall(totalItems),
            abortReason              = abortReason
          ))
        } else if (capExceeded) {
          Task.pure(DispatchWorkersOutput.DispatchResult(
            totalItems      = totalItems,
            successCount    = 0,
            failureCount    = 0,
            results         = Nil,
            resolvedModelId = resolvedModelId,
            abortReason     = Some(s"found $totalItems items; refusing to dispatch more than maxItems=${input.maxItems}.")
          ))
        } else {
          runPipeline(input, ctx, items, resolvedModelId)
        }
      }
    }
  }

  // ---- worker-model resolution ----

  private def resolveWorkerModelId(input: DispatchWorkersInput, ctx: TurnContext): Task[String] = input.workerModelId match {
    case Some(explicit) if explicit.nonEmpty => Task.pure(explicit)
    case _ if input.pipeline.llm.isEmpty     => Task.pure("")
    case _ =>
      def fromStrategy(strategy: ProviderStrategy): Option[String] =
        strategy.candidates(CodingWork)
          .find(_.supportedComplexity.contains(input.complexity))
          .map(_.modelId.value)

      ctx.sigil.accessibleSpaces(ctx.chain, ctx.conversation.id).flatMap { spaces =>
        val ordered = spaces.toList
        def loop(remaining: List[sigil.SpaceId]): Task[Option[String]] = remaining match {
          case Nil => Task.pure(None)
          case space :: rest =>
            ctx.sigil.resolveProviderStrategy(space).flatMap {
              case Some(strategy) => fromStrategy(strategy) match {
                case Some(id) => Task.pure(Some(id))
                case None     => loop(rest)
              }
              case None => loop(rest)
            }
        }
        loop(ordered).map(_.getOrElse(""))
      }
  }

  // ---- pipeline execution ----

  private def runPipeline(input: DispatchWorkersInput,
                          ctx: TurnContext,
                          items: List[Json],
                          modelId: String): Task[DispatchWorkersOutput] = {
    val perItem: List[Task[WorkerResult]] = items.map { item =>
      runOne(input.pipeline, item, modelId, ctx)
        .handleError { t =>
          val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
          Task.pure(WorkerResult.ScriptError(item, msg): WorkerResult)
        }
    }
    Task.parSequenceBounded(perItem, parallelism = math.max(1, input.maxParallel)).map { results =>
      val (successes, failures) = results.partition {
        case _: WorkerResult.Success => true
        case _                       => false
      }
      DispatchWorkersOutput.DispatchResult(
        totalItems      = items.size,
        successCount    = successes.size,
        failureCount    = failures.size,
        results         = results,
        resolvedModelId = modelId
      )
    }
  }

  private def runOne(pipeline: WorkerPipeline,
                     item: Json,
                     modelId: String,
                     ctx: TurnContext): Task[WorkerResult] = {
    val llmStage: Task[Either[WorkerResult, Json]] = pipeline.llm match {
      case None => Task.pure(Right(item))
      case Some(step) => runLlm(step, item, modelId, ctx)
    }
    llmStage.flatMap {
      case Left(failure) => Task.pure(failure)
      case Right(payload) =>
        pipeline.script match {
          case None => Task.pure(WorkerResult.Success(item, payload): WorkerResult)
          case Some(scriptStep) => runScript(scriptStep, item, payload, ctx)
        }
    }
  }

  // ---- LLM step ----

  private def runLlm(step: LlmStep,
                     item: Json,
                     modelId: String,
                     ctx: TurnContext): Task[Either[WorkerResult, Json]] = {
    if (modelId.isEmpty) {
      return Task.pure(Left(WorkerResult.ValidationFailed(item,
        "no worker model id resolved (set `workerModelId` or wire a ProviderStrategy for CodingWork at the input's complexity).")))
    }
    val substitutedSystem = DispatchWorkersTool.substitute(step.systemPrompt, item)
    val substitutedPrompt = DispatchWorkersTool.substitute(step.prompt, item)
    val settings = GenerationSettings(
      temperature     = step.temperature,
      maxOutputTokens = step.maxTokens
    )
    ctx.sigil.providerFor(lightdb.id.Id[sigil.db.Model](modelId), ctx.chain).flatMap { provider =>
      val request = OneShotRequest(
        modelId            = lightdb.id.Id[sigil.db.Model](modelId),
        systemPrompt       = if (substitutedSystem.isEmpty) " " else substitutedSystem,
        userPrompt         = if (substitutedPrompt.isEmpty) " " else substitutedPrompt,
        generationSettings = settings,
        chain              = ctx.chain
      )
      provider(request).toList.map { events =>
        val text = collectText(events)
        if (text.isEmpty)
          Left(WorkerResult.ValidationFailed(item, "worker LLM emitted an empty response"))
        else step.outputSchema match {
          case None      => Right(Obj("text" -> Str(text)))
          case Some(_) =>
            scala.util.Try(JsonParser(text)) match {
              case scala.util.Success(j) => Right(j)
              case scala.util.Failure(t) =>
                Left(WorkerResult.ValidationFailed(item,
                  s"worker LLM response did not parse as JSON (${Option(t.getMessage).getOrElse(t.getClass.getSimpleName)}): ${text.take(120)}"))
            }
        }
      }
    }.handleError { t =>
      Task.pure(Left(WorkerResult.ValidationFailed(item,
        s"worker LLM call failed: ${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}")))
    }
  }

  // ---- script step ----

  private def runScript(step: ScriptStep,
                        item: Json,
                        payload: Json,
                        ctx: TurnContext): Task[WorkerResult] = {
    val executor: Option[ScriptExecutor] = scriptExecutor.orElse(DispatchWorkersTool.scriptExecutorOf(ctx.sigil))
    executor match {
      case None =>
        Task.pure(WorkerResult.Skipped(item,
          "dispatch_workers: pipeline.script requires the host Sigil to mix in ScriptSigil (or an injected ScriptExecutor); neither was available."))
      case Some(exec) =>
        val bindings: Map[String, Any] = Map(
          "input"   -> payload,
          "item"    -> item,
          "context" -> ctx
        )
        exec.execute(step.code, bindings).map { result =>
          val out = scala.util.Try(JsonParser(result)).getOrElse(Str(result))
          // Pattern-match on the script result for the standard
          // `edit_file`-style `Stale(currentHash, ...)` shape so the
          // dispatch surface treats hash mismatches as a first-class
          // partial-success rather than a generic Success.
          out match {
            case o: Obj if o.value.contains("Stale") =>
              val hash = o.value.get("Stale")
                .flatMap {
                  case so: Obj => so.value.get("currentHash").collect { case Str(s, _) => s }
                  case _       => None
                }
                .getOrElse("")
              WorkerResult.Stale(item, hash)
            case _ => WorkerResult.Success(item, out)
          }
        }.handleError { t =>
          val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
          Task.pure(WorkerResult.ScriptError(item, msg): WorkerResult)
        }
    }
  }

  // ---- helpers ----

  private def collectText(events: List[ProviderEvent]): String = {
    val sb = new StringBuilder
    events.foreach {
      case ProviderEvent.ContentBlockDelta(_, t) => sb.append(t)
      case ProviderEvent.TextDelta(t)            => sb.append(t)
      case _                                     => ()
    }
    sb.toString
  }

  private def costNote(complexity: Complexity, items: Int, pipeline: WorkerPipeline): String = {
    val tier = complexity match {
      case Complexity.Low      => "cheapest tier"
      case Complexity.Medium   => "mid tier"
      case Complexity.High     => "high tier"
      case Complexity.VeryHigh => "frontier tier"
    }
    val llmShare = if (pipeline.llm.isDefined) s"$items worker LLM call(s) on $tier" else "no LLM calls"
    val scriptShare = if (pipeline.script.isDefined) s"$items script invocation(s)" else "no script invocations"
    s"$llmShare; $scriptShare"
  }

  private def renderConfirmCall(workers: Int): String =
    s"call dispatch_workers again with the same arguments and confirmed=true to dispatch $workers workers"
}

object DispatchWorkersTool {

  /** Hard cap on `perItemSample` in [[DispatchWorkersOutput.ScopePreview]].
    * Keeps the preview body comfortably under the framework's inline-
    * truncation threshold even for 1000+ item containers. */
  val PreviewSampleSize: Int = 10

  /** Substitute `{{item}}` and `{{item.path.to.field}}` placeholders
    * in `template` against `item`. Unknown paths emit the raw
    * placeholder so the failure mode is visible in the worker
    * prompt rather than silently dropping the substitution. */
  private[dispatch] def substitute(template: String, item: Json): String = {
    if (template.isEmpty) return template
    val pattern = """\{\{item(?:\.([a-zA-Z0-9_.\[\]]+))?\}\}""".r
    pattern.replaceAllIn(template, m => {
      val path = Option(m.group(1))
      val value = path match {
        case None       => item
        case Some(p)    => resolvePath(item, p).getOrElse(item)
      }
      val rendered = value match {
        case Str(s, _)     => s
        case NumInt(n, _)  => n.toString
        case NumDec(d, _)  => d.toString
        case Bool(b, _)    => b.toString
        case fabric.Null   => "null"
        case other         => JsonFormatter.Compact(other)
      }
      java.util.regex.Matcher.quoteReplacement(rendered)
    })
  }

  private def resolvePath(json: Json, path: String): Option[Json] = {
    val parts = path.split("\\.").toList
    parts.foldLeft(Option(json)) {
      case (Some(o: Obj), key) => o.value.get(key)
      case (Some(_: Arr), _)   => None
      case _                   => None
    }
  }

  /** Reflective discovery of an `Option[ScriptExecutor]` on the host
    * Sigil. Apps mixing in `ScriptSigil` expose `scriptExecutor:
    * ScriptExecutor`; we use reflection because `tooling/` cannot
    * statically depend on `script/`'s opt-in mixin (that would force
    * every tooling user to pull in scala3-repl). When the host
    * doesn't have the method, returns `None` and the script step
    * surfaces a structured Skipped result. */
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
