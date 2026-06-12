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
import sigil.provider.{GenerationSettings, OneShotRequest, ProviderEvent, ProviderModel, UnregisteredModelException}
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
        val argsRaw = input.arguments
          .map(a => WorkflowVariableSubstitution.substitute(a, workflow.variables).trim)
          .getOrElse("")
        val argsJson: Json =
          if (argsRaw.isEmpty) obj()
          else scala.util.Try(fabric.io.JsonParser(argsRaw)).getOrElse(obj())
        val parsed: Either[Throwable, Any] = scala.util.Try(tool.inputRW.write(argsJson)).toEither
        parsed match {
          case Left(err) =>
            Task.error(new RuntimeException(
              s"Workflow step '${input.id}' failed to parse arguments for '$toolName': ${err.getMessage}"
            ))
          case Right(decoded) =>
            SyntheticTurnContext.build(host, workflow).flatMap { ctx =>
              val typedInput = decoded.asInstanceOf[ToolInput]
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

  private def runPrompt(host: Sigil, workflow: Workflow, prompt: String): Task[Json] = {
    // Bug #65 — step's `modelId` falls back to a workflow-level
    // default. The default is sourced from the workflow's
    // `variables` map under [[SigilWorkflowVariables.DefaultModelId]]
    // ("__sigil_defaultModelId"). Lets workflow authors pin the model
    // once at workflow creation rather than threading it through
    // every step's input. Reads from the existing Strider
    // variables surface — no Strider schema change required.
    val resolved = input.modelId.map(_.trim).filter(_.nonEmpty)
      .orElse(SigilWorkflowVariables.defaultModelIdOf(workflow))
    resolved match {
      case None =>
        Task.error(new RuntimeException(
          s"Workflow step '${input.id}' has a prompt but no `modelId` (and no `${SigilWorkflowVariables.DefaultModelId}` variable on the workflow). Set one of them to the model the prompt should run against."
        ))
      case Some(s) =>
        val modelId = Id[Model](s)
        // Sigil #374 — the step's id may free-form a registered model under a
        // variant spelling (resolveProviderModel rescues that), or be flat-out
        // unregistered. In the latter case fall back to the workflow's default
        // model rather than throwing and losing the whole run.
        val fallbackId = SigilWorkflowVariables.defaultModelIdOf(workflow)
          .map(d => Id[Model](d)).filter(_.value != modelId.value)
        SyntheticTurnContext.build(host, workflow).flatMap { ctx =>
          resolveModelWithFallback(host, modelId, fallbackId).flatMap { pm =>
            // Resolve provider + registered record at the boundary.
            val provider = pm.provider
            val request = OneShotRequest(
              model = pm.model,
              systemPrompt = "",
              userPrompt = prompt,
              generationSettings = GenerationSettings()
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
    }
  }

  /** Sigil #374 — resolve the step's model (tolerantly — `resolveProviderModel`
    * already rescues case / prefix / `.`-vs-`-` format variants of a registered
    * id). If it is still genuinely unregistered, fall back to the workflow's
    * default model with a warn instead of throwing: a mis-named leaf model id
    * must not lose an otherwise-valid run. With no usable fallback the error
    * propagates (and now settles the run as Failed — #375). */
  private def resolveModelWithFallback(host: Sigil,
                                       modelId: Id[Model],
                                       fallbackId: Option[Id[Model]]): Task[ProviderModel] =
    Task(host.resolveProviderModel(modelId)).handleError {
      case _: UnregisteredModelException if fallbackId.isDefined =>
        val fb = fallbackId.get
        scribe.warn(
          s"Workflow step '${input.id}' model `${modelId.value}` is unregistered; " +
            s"falling back to the workflow default `${fb.value}`."
        )
        Task(host.resolveProviderModel(fb))
      case t => Task.error(t)
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
