package sigil.tool.provider

import fabric.rw.*
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

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
case object PinModelTool extends TypedTool[PinModelInput](
  name = ToolName("pin_model"),
  description =
    """Pin every LLM call in this conversation to one model. Overrides mode strategies, space
      |strategies, and the agent's pinned modelId. Stays in effect until `unpin_model` clears it.
      |
      |Use when the user wants deterministic model selection ("always use local qwen", "stay on
      |gpt-5.5 even when the classifier needs a small model").""".stripMargin,
  examples = List(
    ToolExample("Pin to local llama", PinModelInput("local/qwen3.5-9b")),
    ToolExample("Pin to a frontier model", PinModelInput("openai/gpt-5.5"))
  ),
  keywords = Set(
    "pin", "lock", "force", "stick", "fix", "always", "deterministic",
    "model", "llm", "use"
  )
) {
  override protected def executeTyped(input: PinModelInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
      ModelResolution.resolve(input.modelId, ctx).flatMap {
        case ModelResolutionResult.Unresolved(_, guidance) =>
          Task.pure(Stream.emit[Event](reply(ctx, guidance)))
        case ModelResolutionResult.Resolved(modelId, via) =>
          val noteVia = via match {
            case ModelResolutionResult.Resolution.Alias     => s" (resolved alias '${input.modelId}' → ${modelId.value})"
            case ModelResolutionResult.Resolution.BareModel => s" (interpreted '${input.modelId}' as ${modelId.value})"
            case ModelResolutionResult.Resolution.ExactId   => ""
          }
          ctx.sigil.withDB(_.conversations.transaction(_.modify(ctx.conversation.id) {
            case None       => Task.pure(None)
            case Some(conv) => Task.pure(Some(conv.copy(pinnedModelId = Some(modelId), modified = Timestamp())))
          })).map { _ =>
            Stream.emit[Event](reply(ctx,
              s"Pinned to '${modelId.value}'$noteVia. Every LLM call in this conversation will use this model until `unpin_model` is called."))
          }
      }
    )

  private def reply(ctx: TurnContext, text: String): Message = Message(
    participantId  = ctx.caller,
    conversationId = ctx.conversation.id,
    topicId        = ctx.conversation.currentTopicId,
    content        = Vector(ResponseContent.Text(text)),
    state          = EventState.Complete,
    role           = MessageRole.Tool,
    visibility     = MessageVisibility.All
  )
}
