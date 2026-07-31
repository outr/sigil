package sigil.workflow

import fabric.{Json, Null, obj, str}
import fabric.rw.*
import lightdb.id.Id
import lightdb.progress.ProgressManager
import rapid.Task
import sigil.{Sigil, TurnContext}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, ToolInvoke, ToolOutcome}
import sigil.participant.ParticipantId
import sigil.provider.{GenerationSettings, OneShotRequest, ProviderEvent, ReasoningMode}
import sigil.signal.{EventState, ToolDelta}
import sigil.tool.{ToolInput, ToolName}
import sigil.tool.model.ResponseContent
import strider.Workflow
import strider.step.{Job, Step}

/**
 * Strider [[Job]] subclass that runs a Sigil LLM prompt or tool call
 * as part of a workflow run. Compiled from a [[JobStepInput]] by
 * [[WorkflowStepInputCompiler]]; rehydrated cleanly across persist
 * cycles (no non-serializable references).
 *
 * Execution path:
 *
 *   - `tool` set: look up the named tool from the host Sigil's
 *     finder, parse `arguments` (with `{{var}}` substitution) as
 *     fabric `Json`, decode through the tool's `inputRW`, build a
 *     synthetic [[TurnContext]] anchored on the workflow's
 *     conversation (when present), and call `tool.execute`. The
 *     tool's emitted Messages are coalesced into a single Json
 *     output (`{ results: [...] }`).
 *
 *   - `prompt` set: build a [[OneShotRequest]] to the resolved
 *     provider, accumulate the model's text reply.
 *
 *   - both blank: emit `Null` (allowed; useful for placeholder
 *     steps in development).
 *
 * The host [[sigil.Sigil]] is reached via [[WorkflowHost]] — a
 * process-wide singleton set by [[WorkflowSigil]] at init.
 */
final case class SigilJobStep(input: JobStepInput,
                              id: Id[Step] = Step.id()) extends Job[Json] derives RW {
  override def name: String = input.name.getOrElse(input.id)

  override def continueOnError: Boolean = input.continueOnError
  override def retryCount: Int = input.retryCount
  override def retryDelayMs: Long = input.retryDelayMs

  /** #354 — thread a top-level step's result into the named workflow variable (in addition to
    * `payloads`), so a later step can reference it via `{{output}}` substitution, exactly as a
    * `Loop` collects into its output variable. */
  override def outputVariable: Option[String] = input.output.map(_.trim).filter(_.nonEmpty)

  override def execute(workflow: Workflow, pm: ProgressManager): Task[Json] = {
    val host = WorkflowHost.get
    val toolName = input.tool.map(_.trim).filter(_.nonEmpty)
    val resolvedPrompt = input.prompt
      .map(p => WorkflowVariableSubstitution.substitute(p, workflow.variables))
      .filter(_.nonEmpty)
    toolName match {
      case Some(t) => runTool(host, workflow, t)
      case None => resolvedPrompt match {
        case Some(p) => runPrompt(host, workflow, p)
        case None    => Task.pure(Null)
      }
    }
  }

  /** Resolve `tool` against the host Sigil's `findTools.byName`,
    * decode the workflow-substituted `arguments` through the tool's
    * `inputRW`, run `tool.execute` against a synthetic TurnContext,
    * and coalesce the result. */
  private def runTool(host: Sigil, workflow: Workflow, toolName: String): Task[Json] = {
    host.findTools.byName(ToolName(toolName)).flatMap {
      case None =>
        Task.error(new RuntimeException(s"Workflow step '${input.id}' references unknown tool '$toolName'."))
      case Some(tool) =>
        // Sigil #392 — substitute INTO the parsed args JSON, not into its raw
        // text. The authored `arguments` is valid JSON with `{{var}}` in string
        // positions; parse it first, then place each variable's value as TYPED,
        // properly-escaped JSON. The old string-splice path broke the JSON
        // whenever a value carried quotes/newlines (edited file content!) and
        // `JsonParser(...).getOrElse(obj())` silently collapsed the WHOLE object
        // to `{}`, surfacing as a misleading "missing field" on the first arg.
        val argsTemplate = input.arguments.map(_.trim).getOrElse("")
        val substituted: Either[String, Json] =
          if (argsTemplate.isEmpty) Right(obj())
          else scala.util.Try(fabric.io.JsonParser(argsTemplate)).toOption match {
            case Some(parsedTemplate) =>
              Right(WorkflowVariableSubstitution.substituteJson(parsedTemplate, workflow.variables))
            case None =>
              // Authored args aren't valid JSON on their own (e.g. a placeholder
              // in a non-string position). Fall back to text substitution, then
              // parse — and fail loud rather than collapsing to `{}`.
              val s = WorkflowVariableSubstitution.substitute(argsTemplate, workflow.variables).trim
              scala.util.Try(fabric.io.JsonParser(s)).toEither.left.map(e =>
                s"arguments did not parse as JSON after substitution: ${e.getMessage}")
          }
        substituted match {
          case Left(reason) =>
            Task.error(new RuntimeException(
              s"Workflow step '${input.id}': tool '$toolName' $reason. The step was NOT dispatched."))
          case Right(argsJson) =>
            // Sigil #382 — a resolved tool argument must NEVER still carry a
            // `{{var}}` template. A mis-wired variable reaching a tool —
            // especially a destructive one like `write_file` — overwrote real
            // source files with the literal `{{editedContents}}`. Hard-fail the
            // step instead of dispatching; the run settles as Failed (#375).
            val unresolved = WorkflowVariableSubstitution.unresolvedVarsInJson(argsJson)
            if (unresolved.nonEmpty)
              Task.error(new RuntimeException(
                s"Workflow step '${input.id}': tool '$toolName' arguments still reference unresolved " +
                  s"variable(s) ${unresolved.map(v => s"{{$v}}").mkString(", ")} — the referenced step output " +
                  s"isn't available in scope. The step was NOT dispatched."
              ))
            else {
        // Sigil #396/#398 — a leaf can compute a downstream tool's call as
        // structured output that arrives as a JSON-object STRING (a prompt leaf
        // emits text, so the variable holds the object as a `Str`, often wrapped
        // in a markdown code fence). Coerce such a string into the object the
        // tool wants rather than handing it a bare `Str` ("Expected JSON object
        // but got Str") — the "leaf decides the call, tool applies it" recipe.
        val coerced: Json = SigilJobStep.coerceStringArgs(argsJson)
        // `tool.inputRW` is path-dependently typed as RW[tool.Input], so the
        // decoded value IS the tool's input — no cast.
        val parsed: Either[Throwable, tool.Input] = scala.util.Try(tool.inputRW.write(coerced)).toEither
        parsed match {
          case Left(err) =>
            Task.error(new RuntimeException(
              s"Workflow step '${input.id}' failed to parse arguments for '$toolName': ${err.getMessage}"
            ))
          case Right(typedInput) =>
            SyntheticTurnContext.build(host, workflow).flatMap { ctx =>
              tool.execute(typedInput, ctx, Event.id()).toList.flatMap { signals =>
                // #354 — a tool's real result rides the settling `ToolDelta`'s `output` (the typed
                // `ToolOutput`); `ToolResults` was folded into the invoke (#265), so tools like
                // bash/grep/read_file emit no Message. Read that output first (rendered via the
                // tool's `outputRW`); on a logical Failure surface the reason; only then fall back
                // to coalesced Messages, then a generic marker.
                val settle = signals.reverseIterator.collectFirst {
                  case d: ToolDelta if d.state.contains(EventState.Complete) => d
                }
                def fromMessages: Json = {
                  val texts = signals.collect { case m: Message =>
                    m.content.collect { case ResponseContent.Text(text) => text }.mkString
                  }.filter(_.nonEmpty)
                  if (texts.isEmpty) obj("ok" -> str("done"))
                  else obj("results" -> fabric.Arr(texts.map(t => (str(t): Json)).toVector, None))
                }
                val resultJson: Json = settle match {
                  case Some(d) if d.output.isDefined =>
                    tool.outputRW.read(d.output.get.asInstanceOf[tool.Output])
                  case Some(d) =>
                    d.outcome match {
                      case Some(f: ToolOutcome.Failure) => obj("error" -> str(f.reason))
                      case _                            => fromMessages
                    }
                  case None => fromMessages
                }
                // Sigil #376 — record the call as one settled ToolInvoke in the
                // run's sub-conversation so it's openable.
                persistToolInvocation(host, workflow, ctx, ToolName(toolName), typedInput, settle).map(_ => resultJson)
              }
            }
        }
            }
        }
    }
  }

  private def runPrompt(host: Sigil, workflow: Workflow, prompt: String): Task[Json] =
    SyntheticTurnContext.build(host, workflow).flatMap { ctx =>
      // Sigil #380 — a prompt step never names a model. Route through the
      // conversation's active Mode (its `workType`) + the step's `complexity`
      // tier, exactly as a normal turn does — `routedModelFor` picks a
      // REGISTERED, configured model and degrades down-tier as needed, with the
      // running agent's own model (always registered) as the fallback. So there
      // is no model id to guess, no UnregisteredModelException to reach.
      val workType = ctx.conversation.currentMode.workType.getOrElse(sigil.provider.ConversationWork)
      host.routedCandidateFor(workType, ctx.chain, complexity = input.complexity).flatMap { candidateOpt =>
        val modelId = candidateOpt.map(_.modelId).getOrElse(ctx.model._id)
        val pm = host.resolveProviderModel(modelId)
        val provider = pm.provider
        // Sigil #388 — carry the routed candidate's GenerationSettings (token
        // cap, reasoningMode) into the leaf request instead of dropping them; a
        // fresh GenerationSettings() left a reasoning model uncapped with
        // reasoning on, so it burned the whole budget on the thinking channel
        // and returned empty content. A prompt leaf has no tool-call decision to
        // reason toward, so reasoning is forced OFF regardless of the candidate
        // default — any thinking-channel output here is pure budget waste.
        val settings = candidateOpt.map(_.settings).getOrElse(GenerationSettings())
          .copy(reasoningMode = ReasoningMode.Off)
        val request = OneShotRequest(
          model = pm.model,
          systemPrompt = "",
          userPrompt = prompt,
          generationSettings = settings
        )
        val acc = new java.lang.StringBuilder
        provider(request).evalMap {
          case ProviderEvent.TextDelta(t)            => Task { acc.append(t); () }
          case ProviderEvent.ContentBlockDelta(_, t) => Task { acc.append(t); () }
          case _                                     => Task.unit
        }.drain.flatMap { _ =>
          val response = acc.toString
          // Sigil #376 — record the prompt + the model's reply in the run's
          // sub-conversation so the step is openable.
          persistPromptTurn(host, workflow, ctx, prompt, response, pm.model._id)
            .map(_ => str(response): Json)
        }
      }
    }

  /** Sigil #376 — record a tool step as one settled [[ToolInvoke]] (input +
    * output + outcome) in the run's sub-conversation so the call is openable.
    * `tool.execute` emits only the settling delta (the orchestrator normally
    * supplies the invoke), so we mint the paired, already-Complete invoke here
    * rather than publishing an orphan delta. Best-effort and gated on a bound
    * run with a resolvable author; a publish hiccup never fails the step. */
  private def persistToolInvocation(host: Sigil,
                                    workflow: Workflow,
                                    ctx: TurnContext,
                                    toolName: ToolName,
                                    input: ToolInput,
                                    settle: Option[ToolDelta]): Task[Unit] =
    if (workflow.conversationId.isEmpty) Task.unit
    else
      ctx.chain.headOption match {
        case None => Task.unit
        case Some(author) => Task {
          val invoke = ToolInvoke(
            toolName       = toolName,
            participantId  = author,
            conversationId = ctx.conversation._id,
            topicId        = ctx.conversation.currentTopicId,
            input          = Some(input),
            output         = settle.flatMap(_.output).getOrElse(sigil.tool.ToolOutput.Pending),
            outcome        = settle.flatMap(_.outcome).getOrElse(ToolOutcome.Success),
            state          = EventState.Complete
          )
          // Fire-and-forget — transcript persistence is observability, not the
          // run's critical path; a per-iteration publish must not slow a Loop.
          host.publish(invoke).map(_ => ()).handleError(_ => Task.unit).startUnit()
          ()
        }
      }

  /** Sigil #376 — record a prompt step's prompt + the model's reply as two
    * Messages in the run's sub-conversation so the step is openable (the reply
    * carries `modelId` so the UI badges which model produced it). Best-effort:
    * no-op for an unbound run or when no participant resolves from the chain
    * (mirrors the worker-transcript persistence contract). */
  private def persistPromptTurn(host: Sigil,
                                workflow: Workflow,
                                ctx: TurnContext,
                                promptText: String,
                                responseText: String,
                                modelId: Id[Model]): Task[Unit] =
    if (workflow.conversationId.isEmpty) Task.unit
    else
      ctx.chain.headOption match {
        case None => Task.unit
        case Some(author) => Task {
          val convId = ctx.conversation._id
          val topicId = ctx.conversation.currentTopicId
          val promptMsg = Message(
            participantId = author, conversationId = convId, topicId = topicId,
            content = Vector(ResponseContent.Text(promptText)), state = EventState.Complete)
          val replyMsg = Message(
            participantId = author, conversationId = convId, topicId = topicId,
            content = Vector(ResponseContent.Text(responseText)), modelId = Some(modelId),
            state = EventState.Complete)
          // Fire-and-forget — see persistToolInvocation.
          host.publish(promptMsg).flatMap(_ => host.publish(replyMsg)).map(_ => ())
            .handleError(_ => Task.unit).startUnit()
          ()
        }
      }
}

object SigilJobStep {

  /**
   * Sigil #396/#398 — coerce a `Str` tool-argument that is (possibly
   * code-fenced) JSON-object text into the [[fabric.Obj]] the tool expects.
   * A `Str` that isn't a JSON object — even after stripping a markdown fence —
   * is returned unchanged, so a genuinely non-JSON value still fails the tool's
   * decode with a clear "Expected JSON object but got Str" rather than being
   * silently coerced. Non-`Str` values pass through untouched.
   */
  private[workflow] def coerceStringArgs(argsJson: Json): Json = argsJson match {
    case fabric.Str(s, _) =>
      scala.util.Try(fabric.io.JsonParser(stripCodeFence(s))).toOption.collect { case o: fabric.Obj => o }.getOrElse(argsJson)
    case other => other
  }

  /**
   * Sigil #398 — strip a leading/trailing markdown code fence (```` ``` ```` or
   * ```` ```json ````) that models routinely wrap JSON in even when the prompt
   * says not to. Drops the opening fence line (with any language tag) and the
   * trailing fence; non-fenced input is just trimmed.
   */
  private[workflow] def stripCodeFence(s: String): String = {
    val t = s.trim
    if (t.startsWith("```")) {
      val body      = t.stripPrefix("```")
      val afterLang = body.indexOf('\n') match { case -1 => body; case i => body.substring(i + 1) }
      afterLang.stripSuffix("```").trim
    } else t
  }
}
