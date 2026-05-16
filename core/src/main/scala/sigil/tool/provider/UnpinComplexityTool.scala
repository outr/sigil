package sigil.tool.provider

import fabric.rw.*
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{ComplexityChange, Event, Message, MessageRole, MessageVisibility}
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
  override def paginate: Boolean = false

  override protected def executeTyped(input: UnpinComplexityInput, ctx: TurnContext): Stream[Event] =
    Stream.force(
      // Sigil bug #177 — emit ComplexityChange alongside the
      // confirmation Message so UI consumers can reduce the unpin
      // event without polling Conversation.pinnedComplexity.
      ctx.sigil.withDB(_.conversations.transaction { tx =>
        tx.get(ctx.conversation.id).flatMap {
          case None       => Task.pure(None)
          case Some(conv) =>
            val previous = conv.pinnedComplexity
            tx.upsert(conv.copy(pinnedComplexity = None, modified = Timestamp()))
              .map(_ => Some(previous))
        }
      }).map {
        case None =>
          Stream.emit[Event](reply(ctx,
            "Could not unpin complexity: conversation row not found. Try again from a live session."))
        case Some(previous) =>
          // No-op for the unpinned-already case: still emit so consumers
          // see the user intent (UI can render an "already cleared" pulse).
          Stream.emits[Event](List(
            ComplexityChange(
              participantId  = ctx.caller,
              conversationId = ctx.conversation.id,
              topicId        = ctx.conversation.currentTopicId,
              previousTier   = previous,
              newTier        = None,
              reason         = ComplexityChange.Reason.Unpinned
            ),
            reply(ctx,
              "Cleared the conversation's pinned complexity tier. Routing reverts to per-message classification.")
          ))
      }
    )

  private def reply(ctx: TurnContext, text: String): Message = Message(
    participantId  = ctx.caller,
    conversationId = ctx.conversation.id,
    topicId        = ctx.conversation.currentTopicId,
    content        = Vector(ResponseContent.Text(text)),
    state          = EventState.Complete,
    role           = MessageRole.Tool,
    // Sigil bug #164 — Agents-only so the agent's mandatory `respond`
    // is the sole user-facing confirmation, not a duplicate of the
    // tool's own text.
    visibility     = MessageVisibility.Agents
  )
}
