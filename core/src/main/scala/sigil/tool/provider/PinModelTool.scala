package sigil.tool.provider

import fabric.rw.*
import lightdb.time.Timestamp
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{
  DiscoverySpec, Effect, MutationTargeting, Resolution, TextToolOutput, Tool, ToolExample, ToolIO, ToolInput, ToolName, ToolProfile,
  ToolResult, ToolSpec
}

case class PinModelInput(modelId: String) extends ToolInput derives RW

/**
 * Pin the conversation's main agent turn to a single model.
 * Overrides mode-driven strategy selection AND space-level strategy
 * assignment for the agent's turn until `unpin_model` clears it.
 *
 * Scope (#357): by default the pin governs only the main agent turn.
 * Framework auxiliary calls (topic classifier, memory extractor,
 * progress checkpoint, curate compression) keep routing through the
 * app's `routedModelFor` to the cheapest viable tier for their work
 * type — usually the right default, since classification / extraction
 * / summarization don't need a frontier model. Apps that want a pin to
 * mean "every LLM call in this conversation" set
 * `Sigil.pinCoversAuxiliaryCalls = true`, which routes the auxiliary
 * calls to the pinned model too.
 *
 * Not auto-registered. Apps that want this surface add the tool
 * to their `staticTools` list.
 */
case object PinModelTool extends Tool {
  type Input = PinModelInput
  type Output = TextToolOutput
  val io: ToolIO[PinModelInput, TextToolOutput] = ToolIO.derived[PinModelInput, TextToolOutput].withExamples(
    ToolExample("Pin to local llama", PinModelInput("local/qwen3.5-9b")),
    ToolExample("Pin to a frontier model", PinModelInput("openai/gpt-5.5"))
  )
  override val name = ToolName("pin_model")
  override val description =
    """Pin this conversation's agent turn to one model. Overrides mode strategies and space
      |strategies for the main turn. Stays in effect until `unpin_model` clears it. (Framework
      |auxiliary calls — topic classifier, memory extractor, summarization — stay on the app's
      |cost-first routing unless the app opts them in.)
      |
      |Use when the user wants deterministic model selection ("always use local qwen", "stay on
      |gpt-5.5 for my replies").""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set(
      "pin",
      "lock",
      "force",
      "stick",
      "fix",
      "always",
      "deterministic",
      "model",
      "llm",
      "use"
    ))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: PinModelInput,
                            ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    ModelResolution.resolve(input.modelId, ctx).flatMap {
      case ModelResolutionResult.Unresolved(_, guidance) =>
        Task.pure(ToolResult.failure(guidance))
      case ModelResolutionResult.Resolved(modelId, via) =>
        val noteVia = via match {
          case ModelResolutionResult.Resolution.Alias => s" (resolved alias '${input.modelId}' → ${modelId.value})"
          case ModelResolutionResult.Resolution.BareModel => s" (interpreted '${input.modelId}' as ${modelId.value})"
          case ModelResolutionResult.Resolution.ExactId => ""
        }
        ctx.sigil.withDB(_.conversations.transaction(_.modify(ctx.conversation.id) {
          case None => Task.pure(None)
          case Some(conv) => Task.pure(Some(conv.copy(pinnedModelId = Some(modelId), modified = Timestamp())))
        })).map {
          case None =>
            ToolResult.failure(
              "Could not pin model: conversation row not found. Try again from a live session.")
          case Some(_) =>
            ToolResult.Success(TextToolOutput(
              s"Pinned to '${modelId.value}'$noteVia. The agent's turns in this conversation will use this model until `unpin_model` is called."))
        }
    }
}
