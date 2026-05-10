package sigil.tool.provider

import fabric.rw.*
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.{ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

case class UnpinModelInput() extends ToolInput derives RW

/**
 * Clear the conversation's pinned model — dispatch reverts to the
 * normal stack (mode strategy → space strategy → agent's pinned
 * modelId fallback). Companion to [[PinModelTool]]. Not
 * auto-registered.
 */
case object UnpinModelTool extends TypedTool[UnpinModelInput](
  name = ToolName("unpin_model"),
  description =
    """Clear the conversation's pinned model. Dispatch reverts to mode + space strategies and the
      |agent's pinned modelId fallback. No-op when nothing was pinned.""".stripMargin,
  keywords = Set("unpin", "unlock", "clear", "auto", "default", "model")
) {
  override protected def executeTyped(input: UnpinModelInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
      ctx.sigil.withDB(_.conversations.transaction(_.modify(ctx.conversation.id) {
        case None       => Task.pure(None)
        case Some(conv) => Task.pure(Some(conv.copy(pinnedModelId = None, modified = Timestamp())))
      })).map { _ =>
        Stream.emit[Event](reply(ctx, "Cleared the conversation's pinned model. Dispatch reverts to the normal strategy stack."))
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
