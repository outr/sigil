package sigil.tool.provider

import fabric.rw.*
import lightdb.time.Timestamp
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}

case class PinModelInput(modelId: String) extends ToolInput derives RW

/**
 * Pin every LLM dispatch in this conversation to a single model.
 * Overrides mode-driven strategy selection AND space-level
 * strategy assignment — the agent's main turn AND framework
 * auxiliary calls (topic classifier, memory extractor, curate
 * compression) all route to the pinned model until `unpin_model`
 * clears it.
 *
 * Not auto-registered. Apps that want this surface add the tool
 * to their `staticTools` list.
 */
case object PinModelTool extends Tool {
  type Input = PinModelInput
  type Output = TextToolOutput
  val inputRW = summon[RW[PinModelInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("pin_model")
  val description =
    """Pin every LLM call in this conversation to one model. Overrides mode strategies, space
      |strategies, and the agent's pinned modelId. Stays in effect until `unpin_model` clears it.
      |
      |Use when the user wants deterministic model selection ("always use local qwen", "stay on
      |gpt-5.5 even when the classifier needs a small model").""".stripMargin
  override val examples = List(
    ToolExample("Pin to local llama", PinModelInput("local/qwen3.5-9b")),
    ToolExample("Pin to a frontier model", PinModelInput("openai/gpt-5.5"))
  )
  override val keywords = Set(
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
  )

  override def executeResult(input: PinModelInput,
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
        })).map { _ =>
          ToolResult.Success(TextToolOutput(
            s"Pinned to '${modelId.value}'$noteVia. Every LLM call in this conversation will use this model until `unpin_model` is called."))
        }
    }
}
