package sigil.tool.provider

import fabric.rw.*
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.{ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

case class UnpinComplexityInput() extends ToolInput derives RW

/**
 * Clear the conversation's pinned complexity tier — routing
 * reverts to [[sigil.provider.RoutedStrategy.inferComplexity]]'s
 * per-message classification. Companion to [[PinComplexityTool]].
 * Not auto-registered. Bug #152.
 */
case object UnpinComplexityTool extends TypedTool[UnpinComplexityInput](
  name = ToolName("unpin_complexity"),
  description =
    """Clear the conversation's pinned complexity tier. Every turn
      |returns to per-message `inferComplexity` classification. No-op
      |when nothing was pinned.""".stripMargin,
  keywords = Set("unpin", "unlock", "clear", "auto", "default", "complexity", "tier")
) {
  override protected def executeTyped(input: UnpinComplexityInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
      ctx.sigil.withDB(_.conversations.transaction(_.modify(ctx.conversation.id) {
        case None       => Task.pure(None)
        case Some(conv) => Task.pure(Some(conv.copy(pinnedComplexity = None, modified = Timestamp())))
      })).map { _ =>
        Stream.emit[Event](reply(ctx,
          "Cleared the conversation's pinned complexity tier. Routing reverts to per-message classification."))
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
