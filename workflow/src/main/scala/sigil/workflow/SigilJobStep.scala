package sigil.workflow

import fabric.{Json, Null, obj, str}
import fabric.rw.*
import lightdb.id.Id
import lightdb.progress.ProgressManager
import rapid.Task
import sigil.{Sigil, TurnContext}
import sigil.conversation.{Conversation, ConversationView, TurnInput}
import sigil.db.Model
import sigil.event.Message
import sigil.participant.ParticipantId
import sigil.provider.{GenerationSettings, OneShotRequest, ProviderEvent}
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
  override def name: String = if (input.name.nonEmpty) input.name else input.id

  override def continueOnError: Boolean = input.continueOnError
  override def retryCount: Int = input.retryCount
  override def retryDelayMs: Long = input.retryDelayMs

  override def execute(workflow: Workflow, pm: ProgressManager): Task[Json] = {
    val host = WorkflowHost.get
    val toolName = input.tool.trim
    val resolvedPrompt = WorkflowVariableSubstitution.substitute(input.prompt, workflow.variables)
    if (toolName.nonEmpty) runTool(host, workflow, toolName)
    else if (resolvedPrompt.nonEmpty) runPrompt(host, resolvedPrompt)
    else Task.pure(Null)
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
        val argsRaw = WorkflowVariableSubstitution.substitute(input.arguments, workflow.variables).trim
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
              tool.execute(typedInput, ctx).toList.map { evs =>
                val texts = evs.collect { case m: Message =>
                  m.content.collect { case ResponseContent.Text(text) => text }.mkString
                }.filter(_.nonEmpty)
                if (texts.isEmpty) obj("ok" -> str("done"))
                else obj("results" -> fabric.Arr(texts.map(t => (str(t): Json)).toVector, None))
              }
            }
        }
    }
  }

  private def runPrompt(host: Sigil, prompt: String): Task[Json] = {
    val modelIdStr = input.modelId.trim
    if (modelIdStr.isEmpty)
      Task.error(new RuntimeException(
        s"Workflow step '${input.id}' has a prompt but no `modelId`. Set `modelId` to the model the prompt should run against."
      ))
    else {
      val modelId = Id[Model](modelIdStr)
      host.providerFor(modelId, Nil).flatMap { provider =>
        val request = OneShotRequest(
          modelId = modelId,
          systemPrompt = "",
          userPrompt = prompt,
          generationSettings = GenerationSettings()
        )
        val acc = new java.lang.StringBuilder
        provider(request).evalMap {
          case ProviderEvent.TextDelta(t)            => Task { acc.append(t); () }
          case ProviderEvent.ContentBlockDelta(_, t) => Task { acc.append(t); () }
          case _                                     => Task.unit
        }.drain.map(_ => str(acc.toString): Json)
      }
    }
  }
}
