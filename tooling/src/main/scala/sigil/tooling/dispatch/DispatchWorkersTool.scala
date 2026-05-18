package sigil.tooling.dispatch

import fabric.io.{JsonFormatter, JsonParser}
import fabric.rw.*
import fabric.{Json, Obj, Str, Arr, Bool, NumInt, NumDec}
import rapid.Task
import sigil.TurnContext
import sigil.provider.{CodingWork, Complexity, GenerationSettings, OneShotRequest, ProviderEvent, ProviderStrategy}
import sigil.script.ScriptExecutor
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}
import sigil.tool.output.ToolOutputNode

/**
 * Generic parallel LLM-and-script per-item pipeline. Replaces the
 * three-tool refactor session (`refactor_with_instruction`,
 * `refactor_apply`, `refactor_cancel`) with a single composable
 * primitive that works for any "do something with each of these
 * items" shape:
 *
 *   - LLM-only — classify / annotate / categorise each item.
 *   - LLM + script — propose an edit per item, apply via the script
 *     step (e.g. `edit_file` with its safe-edit `expectedHash` flow).
 *   - Script-only — deterministic per-item transformation, no model
 *     cost.
 *
 * Two-phase confirm mirrors the prior refactor flow: `confirmed =
 * false` (default) returns a scope preview without dispatching any
 * worker; `confirmed = true` runs the pipeline and returns
 * [[DispatchWorkersOutput.Dispatched]].
 *
 * Item-source adapters live in [[WorkerItemSourceAdapter]]'s
 * registry — apps with custom tools whose output naturally
 * dispatches register additional adapters; the framework ships
 * adapters for `grep`, `lsp_find_references`, and
 * `lsp_workspace_symbols`.
 *
 * `ScriptStep` requires the host Sigil to mix in `ScriptSigil`. When
 * the host doesn't, items requesting script execution surface a
 * structured [[WorkerResult.Skipped]] outcome.
 */
final class DispatchWorkersTool(scriptExecutor: Option[ScriptExecutor] = None)
  extends TypedOutputTool[DispatchWorkersInput, DispatchWorkersOutput](
    name = ToolName("dispatch_workers"),
    description =
      """Run a per-item pipeline (optional LLM step + optional script step) over a list of items in
        |parallel. Composable with grep / LSP / any tool whose output is a list, plus inline lists, files,
        |and conversation extracts.
        |
        |Two-phase confirm. First call with `confirmed = false` (the default): resolves the worker-item
        |list and worker model id and returns a scope preview without dispatching any worker. Review,
        |then re-call with `confirmed = true` to run.
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
        |Worker item sources:
        |  - `FromList(items)` — explicit list of JSON values.
        |  - `FromCall(callId, groupBy)` — project a prior tool call's persisted output via the
        |    registered adapter for that tool's name.
        |  - `FromFile(filePath, parser)` — read a file and split into items (Lines / JsonArray /
        |    JsonLines / CsvHeaders).
        |  - `FromConversation(messageId, extractor)` — apps register conversation extractors via
        |    the adapter registry under the `conversation:<extractor>` key.
        |
        |Required: `complexity` (routing tier for the worker model). `workerModelId` overrides routing.
        |`maxParallel` (default 5) caps concurrent worker invocations; `maxItems` (default 10000) caps
        |the total item count to avoid runaway billing.""".stripMargin,
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
        "Preview a refactor that removes bug-number comments across grep results (confirmed=false)",
        DispatchWorkersInput(
          complexity = Complexity.Low,
          items      = WorkerItemSource.FromList(List(
            Obj("filePath" -> Str("a.scala"), "matchCount" -> NumInt(2)),
            Obj("filePath" -> Str("b.scala"), "matchCount" -> NumInt(1))
          )),
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
          items      = WorkerItemSource.FromList(List(Str("hello"), Str("goodbye"))),
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
      return Task.pure(DispatchWorkersOutput.Dispatched(
        totalItems      = 0,
        successCount    = 0,
        failureCount    = 0,
        results         = Nil,
        resolvedModelId = "",
        abortReason     = Some("dispatch_workers: pipeline must declare at least one of `llm` or `script` — empty pipelines have nothing to run.")
      ))
    }
    resolveItems(input.items, ctx).flatMap { items =>
      val totalItems = items.size
      val capExceeded = totalItems > input.maxItems
      resolveWorkerModelId(input, ctx).flatMap { resolvedModelId =>
        if (!input.confirmed) {
          val previewLimit = math.min(totalItems, 200)
          val abortReason: Option[String] =
            if (capExceeded)
              Some(s"found $totalItems items; refusing to dispatch more than maxItems=${input.maxItems}. " +
                "Narrow the item source or raise the cap, then re-preview.")
            else None
          Task.pure(DispatchWorkersOutput.Scope(
            items             = items.take(previewLimit),
            totalItems        = totalItems,
            resolvedModelId   = resolvedModelId,
            estimatedCostNote = costNote(input.complexity, totalItems, input.pipeline),
            confirmCall       = renderConfirmCall(input),
            abortReason       = abortReason
          ))
        } else if (capExceeded) {
          Task.pure(DispatchWorkersOutput.Dispatched(
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

  // ---- item-source resolution ----

  private def resolveItems(source: WorkerItemSource, ctx: TurnContext): Task[List[Json]] = source match {
    case WorkerItemSource.FromList(items) => Task.pure(items)
    case WorkerItemSource.FromCall(callId, groupBy) =>
      DispatchWorkersTool.resolveFromCall(ctx, callId, groupBy)
    case WorkerItemSource.FromFile(filePath, parser) =>
      DispatchWorkersTool.resolveFromFile(ctx, filePath, parser)
    case WorkerItemSource.FromConversation(messageId, extractor) =>
      WorkerItemSourceAdapter.lookup(s"conversation:$extractor") match {
        case Some(adapter) =>
          // Conversation extractors get the message's persisted row
          // (synthetic node) so they can carry the message text +
          // metadata; apps register the extractor and decide the shape.
          ctx.sigil.withDB(_.events.transaction(_.get(messageId.asInstanceOf[lightdb.id.Id[sigil.event.Event]]))).flatMap {
            case Some(ev) =>
              val payload = sigil.event.Event.rw.read(ev)
              val syntheticRow = ToolOutputNode(
                conversationId = ctx.conversation.id,
                callId         = messageId.asInstanceOf[lightdb.id.Id[sigil.event.Event]],
                referenceId    = messageId.value,
                level          = 0,
                ordinal        = 0,
                hasChildren    = false,
                payload        = payload,
                expiresAt      = lightdb.time.Timestamp(0L)
              )
              adapter.itemsFor(List(syntheticRow), ctx)
            case None => Task.pure(Nil)
          }
        case None =>
          Task.error(new RuntimeException(
            s"dispatch_workers: no conversation extractor registered for 'conversation:$extractor'. " +
              "Apps wire extractors via WorkerItemSourceAdapter.register."
          ))
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
      DispatchWorkersOutput.Dispatched(
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

  private def renderConfirmCall(input: DispatchWorkersInput): String =
    s"dispatch_workers(...same parameters..., confirmed = true)"
}

object DispatchWorkersTool {

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

  /** Read a prior tool call's persisted rows and project to worker
    * items via the registered adapter for the originating tool's
    * name. Applies the `groupBy` policy to the adapter's output. */
  private[dispatch] def resolveFromCall(ctx: TurnContext,
                                        callId: lightdb.id.Id[sigil.event.Event],
                                        groupBy: GroupBy): Task[List[Json]] = {
    val convId = ctx.conversation.id
    ctx.sigil.withDB(_.events.transaction(_.get(callId))).flatMap {
      case Some(ti: sigil.event.ToolInvoke) =>
        val toolName = ti.toolName.value
        WorkerItemSourceAdapter.lookup(toolName) match {
          case None =>
            Task.error(new RuntimeException(
              s"dispatch_workers: no adapter registered for tool '$toolName'. " +
                "Apps wire adapters via WorkerItemSourceAdapter.register; the framework ships " +
                "adapters for grep, lsp_find_references, and lsp_workspace_symbols."
            ))
          case Some(adapter) =>
            ctx.sigil.withDB(_.toolOutputs.transaction(_.list)).flatMap { all =>
              val rows = all.filter(r => r.conversationId == convId && r.callId == callId)
              adapter.itemsFor(rows, ctx).map { items =>
                groupBy match {
                  case GroupBy.None         => items
                  case GroupBy.TopLevelOnly =>
                    val topLevelRows = rows.filter(_.level == 0)
                    val topPayloads  = topLevelRows.sortBy(_.ordinal).map(_.payload).toSet
                    items.filter(topPayloads.contains)
                  case GroupBy.ByKey(key) =>
                    val grouped = items.groupBy {
                      case o: Obj => o.value.get(key).map(stringify).getOrElse("")
                      case _      => ""
                    }
                    grouped.toList.sortBy(_._1).map { case (groupKey, members) =>
                      Obj(
                        key     -> Str(groupKey),
                        "items" -> Arr(members.toVector)
                      )
                    }
                }
              }
            }
        }
      case _ =>
        Task.error(new RuntimeException(
          s"dispatch_workers: callId ${callId.value} does not resolve to a ToolInvoke event in this conversation."
        ))
    }
  }

  private def stringify(j: Json): String = j match {
    case Str(s, _)    => s
    case NumInt(n, _) => n.toString
    case NumDec(d, _) => d.toString
    case other        => JsonFormatter.Compact(other)
  }

  /** Read a file via the host's filesystem context and split into
    * worker items per the [[ItemParser]] policy. */
  private[dispatch] def resolveFromFile(ctx: TurnContext,
                                        filePath: String,
                                        parser: ItemParser): Task[List[Json]] = {
    val fs: sigil.tool.fs.FileSystemContext = fileSystemContextOf(ctx.sigil)
    sigil.tool.fs.WorkspacePathResolver.resolve(ctx, filePath).flatMap { resolved =>
      fs.readFile(resolved).map { content =>
        parser match {
          case ItemParser.Lines =>
            content.linesIterator.zipWithIndex.collect {
              case (line, idx) if line.trim.nonEmpty =>
                Obj("line" -> Str(line), "lineNumber" -> NumInt(idx + 1))
            }.toList
          case ItemParser.JsonArray =>
            JsonParser(content) match {
              case a: Arr => a.value.toList
              case other  => List(other)
            }
          case ItemParser.JsonLines =>
            content.linesIterator
              .filter(_.trim.nonEmpty)
              .map(JsonParser(_))
              .toList
          case ItemParser.CsvHeaders =>
            val lines = content.linesIterator.filter(_.nonEmpty).toList
            if (lines.size <= 1) Nil
            else {
              val headers = lines.head.split(",").toList.map(_.trim)
              lines.tail.map { row =>
                val cells = row.split(",", -1).toList.map(_.trim)
                Obj(headers.zip(cells.padTo(headers.size, "")).map { case (k, v) =>
                  k -> (Str(v): Json)
                }*)
              }
            }
        }
      }
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

  /** Reflective lookup for `fileSystemContext` on the host Sigil.
    * `ToolingSigil` exposes one; apps mixing other traits may also.
    * Falls back to an unbounded local context when the host doesn't
    * expose one. */
  private def fileSystemContextOf(host: AnyRef): sigil.tool.fs.FileSystemContext = {
    val cls = host.getClass
    val method = scala.util.Try(cls.getMethod("fileSystemContext")).toOption
    method.flatMap { m =>
      scala.util.Try(m.invoke(host)) match {
        case scala.util.Success(value: sigil.tool.fs.FileSystemContext) => Some(value)
        case _                                                          => None
      }
    }.getOrElse(new sigil.tool.fs.LocalFileSystemContext(basePath = None))
  }
}
