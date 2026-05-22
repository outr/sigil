package sigil.tool.consult

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.{Sigil, TurnContext}
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.provider.{GenerationSettings, OneShotRequest, ProviderEvent, StopReason}
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}

import scala.reflect.ClassTag

/**
 * One-shot LLM consultation. Two surfaces on the same object:
 *
 * 1. `executeResult` — the LLM-callable path. The agent invokes the tool
 *    with system + user prompts; the consulted model returns free-form
 *    text, which becomes the tool's [[TextToolOutput]] result.
 *
 * 2. [[invoke]] — the framework-callable path. Returns a typed
 *    `Option[I]` directly, no events emitted. Used by framework
 *    machinery that needs structured sub-decisions.
 */
case object ConsultTool extends Tool {
  type Input  = ConsultInput
  type Output = TextToolOutput
  val inputRW: RW[ConsultInput]    = summon[RW[ConsultInput]]
  val outputRW: RW[TextToolOutput] = summon[RW[TextToolOutput]]

  val name: ToolName = ToolName("consult")
  val description: String =
    """Consult another model — or yourself with no conversation history — for a focused sub-question.
      |
      |Use this when:
      |  - the current model isn't strong enough for a hard sub-task and a stronger model should answer
      |  - you want a fresh perspective without the current conversation's context biasing the answer
      |  - you want to delegate a focused, self-contained sub-question and read its answer
      |
      |The consulted model receives ONLY the `systemPrompt` and `userPrompt` you supply — no conversation
      |history, no tools beyond what the framework forces. Its answer comes back as a Message you read on
      |your next turn.
      |
      |`modelId` — the model to consult (e.g. "anthropic/claude-opus-4-7", "openai/gpt-5"). Use any model
      |the framework knows about.
      |
      |`systemPrompt` — set the consulted model's role / context for this question. One paragraph max.
      |
      |`userPrompt` — the actual question or task. Be specific; the consulted model has no other context.""".stripMargin

  override val examples: List[ToolExample] = List(
    ToolExample(
      "Ask a stronger model for a focused legal interpretation",
      ConsultInput(
        modelId = Id("anthropic/claude-opus-4-7"),
        systemPrompt = "You are a careful legal reasoner. Quote relevant clauses verbatim.",
        userPrompt = "Given this contract clause, does it permit unilateral termination? <clause text>"
      )
    )
  )


  override def executeResult(input: ConsultInput, context: TurnContext): Task[ToolResult[TextToolOutput]] =
    context.sigil.providerFor(input.modelId, context.chain).flatMap { provider =>
      val request = OneShotRequest(
        modelId = input.modelId,
        systemPrompt = input.systemPrompt,
        userPrompt = input.userPrompt,
        chain = context.chain
      )
      provider(request).toList.map { events =>
        val text = collectText(events)
        ToolResult.success(TextToolOutput(if (text.isEmpty) "(no response)" else text))
      }
    }

  /**
   * Framework-facing typed consult. Builds a one-shot provider request
   * forced to invoke `tool` (`tool_choice = "required"`), drains the
   * result, and returns the parsed input cast to `I` (or `None` if the
   * model didn't produce a tool call of the expected type).
   */
  def invoke[I <: ToolInput: ClassTag](sigil: Sigil,
                                       modelId: Id[Model],
                                       chain: List[ParticipantId],
                                       systemPrompt: String,
                                       userPrompt: String,
                                       tool: Tool,
                                       // A bounded `maxOutputTokens` is mandatory:
                                       // thinking-mode models (qwen via llama.cpp,
                                       // DeepSeek-R1, …) burn unbounded
                                       // `reasoning_content` tokens before the
                                       // structured tool_call lands — an unbounded
                                       // classifier consult can run for minutes,
                                       // emit tens of thousands of reasoning tokens,
                                       // and hit `finish_reason: length` with zero
                                       // tool_calls. Default is
                                       // [[GenerationSettings.classifierDefault]]
                                       // (bounded output + reasoning off). Framework-
                                       // internal consults declare a per-shape tight
                                       // cap via [[FrameworkConsult.consultSettings]]
                                       // — pass [[settingsFor]] (or use
                                       // [[invokeRouted]]) to apply it. Callers that
                                       // need long-form generation override per call.
                                       generationSettings: GenerationSettings =
                                         GenerationSettings.classifierDefault): Task[Option[I]] =
    invokeRich[I](sigil, modelId, chain, systemPrompt, userPrompt, tool, generationSettings).map {
      case ConsultOutcome.Parsed(v) => Some(v)
      case _                        => None
    }

  /**
   * Richer-shape variant of [[invoke]] (sigil bug #197). Returns a
   * [[ConsultOutcome]] that distinguishes:
   *
   *   - [[ConsultOutcome.Parsed]]    — model emitted the expected tool_call.
   *   - [[ConsultOutcome.NoOpinion]] — stream completed naturally with no
   *                                    matching tool_call.
   *   - [[ConsultOutcome.Truncated]] — stream closed with
   *                                    `finish_reason: length` and no matching
   *                                    tool_call (the model ran out of output
   *                                    budget before forming the structured
   *                                    answer); carries token-usage counts when
   *                                    the provider surfaces them.
   *   - [[ConsultOutcome.Failed]]    — wire-level / parse exception.
   *
   * Callers whose default on `None` is "use the fallback silently"
   * (`Sigil.classifyForRoute`, `classifyTopicShift`, app-supplied
   * classifiers wired into a `ProviderStrategy`) should switch to
   * this variant so they can surface a `FrameworkWorkflowNotice` on
   * `Truncated` / `Failed` rather than absorbing the gap.
   */
  def invokeRich[I <: ToolInput: ClassTag](sigil: Sigil,
                                           modelId: Id[Model],
                                           chain: List[ParticipantId],
                                           systemPrompt: String,
                                           userPrompt: String,
                                           tool: Tool,
                                           generationSettings: GenerationSettings =
                                             GenerationSettings.classifierDefault): Task[ConsultOutcome[I]] = {
    val ct = summon[ClassTag[I]]
    // Wrap the full chain (including `providerFor` resolution and
    // request construction) so any throwable surfaces as
    // `ConsultOutcome.Failed` rather than escaping to the caller.
    // Matches the legacy `invoke` outer-handleError shape; without
    // this, classifier consults whose providerFor itself throws
    // (e.g. test fixtures that didn't wire a provider) silently
    // tear down the surrounding caller's task chain.
    sigil.providerFor(modelId, chain).flatMap { provider =>
      val request = OneShotRequest(
        modelId = modelId,
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        generationSettings = generationSettings,
        tools = Vector(tool),
        chain = chain
      )
      provider(request).toList.map { events =>
        val parsed = events.collectFirst {
          case ProviderEvent.ToolCallComplete(_, p) if ct.runtimeClass.isInstance(p) =>
            p.asInstanceOf[I]
        }
        parsed match {
          case Some(v) => ConsultOutcome.Parsed(v)
          case None =>
            val usage = events.collectFirst { case ProviderEvent.Usage(u) => u }
            val stop  = events.collectFirst { case ProviderEvent.Done(reason) => reason }
            stop match {
              case Some(StopReason.MaxTokens) => ConsultOutcome.truncated(usage)
              case _                          => ConsultOutcome.NoOpinion
            }
        }
      }
    }.handleError { t =>
      Task.pure(ConsultOutcome.Failed(t))
    }
  }

  /**
   * Routing-aware [[invoke]]. Resolves the concrete model from the
   * consult's declared [[FrameworkConsult.consultWorkType]] via
   * `Sigil.routedModelFor` so the call lands on the cheapest viable
   * tier for that work category (classification / summarization)
   * instead of inheriting the calling agent's conversation-tier
   * model. `fallbackModelId` is used when no
   * [[sigil.provider.ProviderStrategy]] applies — pass the model the
   * caller would otherwise have used directly.
   *
   * `generationSettings = None` applies the consult's tight
   * [[FrameworkConsult.consultSettings]] (bounded output +
   * `ReasoningMode.Off`); callers whose output size is per-call
   * (summarization) pass an explicit override.
   *
   * Use this for every framework-internal consult: it threads both
   * cost levers — cheap-tier routing and the tight output cap — from
   * the consult tool's own declaration, so no call site re-specifies
   * either.
   */
  def invokeRouted[I <: ToolInput: ClassTag](sigil: Sigil,
                                             tool: Tool & FrameworkConsult,
                                             chain: List[ParticipantId],
                                             fallbackModelId: Id[Model],
                                             systemPrompt: String,
                                             userPrompt: String,
                                             generationSettings: Option[GenerationSettings] = None): Task[Option[I]] =
    sigil.routedModelFor(tool.consultWorkType, chain, fallbackModelId).flatMap { modelId =>
      invoke[I](sigil, modelId, chain, systemPrompt, userPrompt, tool,
        generationSettings.getOrElse(settingsFor(tool)))
    }

  /**
   * Canonical generation settings for a consult call. Framework-
   * internal consults mix in [[FrameworkConsult]] and declare a tight
   * `consultSettings` sized to their own output shape (bounded
   * `maxOutputTokens` + `ReasoningMode.Off`); this returns that value
   * directly so every call site picks up the per-consult cap without
   * repeating it. Any other tool falls back to
   * [[GenerationSettings.classifierDefault]] — the conservative
   * bounded-output default for narrow structured-payload consults.
   */
  def settingsFor(tool: Tool): GenerationSettings = tool match {
    case fc: FrameworkConsult => fc.consultSettings
    case _                    => GenerationSettings.classifierDefault
  }

  private[consult] def collectText(events: List[ProviderEvent]): String = {
    val sb = new StringBuilder
    events.foreach {
      case ProviderEvent.ContentBlockDelta(_, t) => sb.append(t)
      case ProviderEvent.TextDelta(t)            => sb.append(t)
      case _                                     => ()
    }
    sb.toString
  }
}
