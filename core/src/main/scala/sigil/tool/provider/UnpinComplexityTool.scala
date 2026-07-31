package sigil.tool.provider

import fabric.rw.*
import lightdb.time.Timestamp
import rapid.Task
import sigil.tool.ToolContext
import sigil.event.ComplexityChange
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, Resolution, TextToolOutput, Tool, ToolIO, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}

case class UnpinComplexityInput() extends ToolInput derives RW

/**
  * Clear the conversation's pinned complexity tier — routing
  * reverts to [[sigil.provider.RoutedStrategy.inferComplexity]]'s
  * per-message classification. Companion to [[PinComplexityTool]].
  * Not auto-registered.
  */
case object UnpinComplexityTool extends Tool {
  type Input  = UnpinComplexityInput
  type Output = TextToolOutput
  val io: ToolIO[UnpinComplexityInput, TextToolOutput] = ToolIO.derived[UnpinComplexityInput, TextToolOutput]
  override val name = ToolName("unpin_complexity")
  override val description =
    """Clear the conversation's pinned complexity tier. Every turn
      |returns to per-message `inferComplexity` classification. No-op
      |when nothing was pinned.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("unpin", "unlock", "clear", "auto", "default", "complexity", "tier"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: UnpinComplexityInput,
                            ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    ctx.sigil.withDB(_.conversations.transaction { tx =>
      tx.get(ctx.conversation.id).flatMap {
        case None       => Task.pure(None)
        case Some(conv) =>
          val previous = conv.pinnedComplexity
          tx.upsert(conv.copy(pinnedComplexity = None, modified = Timestamp()))
            .map(_ => Some(previous))
      }
    }).flatMap {
      case None =>
        Task.pure(ToolResult.failure(
          "Could not unpin complexity: conversation row not found. Try again from a live session."))
      case Some(previous) =>
        // No-op for the unpinned-already case: still emit so consumers
        // see the user intent (UI can render an "already cleared" pulse).
        ctx.emit(ComplexityChange(
          participantId  = ctx.caller,
          conversationId = ctx.conversation.id,
          topicId        = ctx.conversation.currentTopicId,
          previousTier   = previous,
          newTier        = None,
          reason         = ComplexityChange.Reason.Unpinned
        )).map(_ => ToolResult.Success(TextToolOutput(
          "Cleared the conversation's pinned complexity tier. Routing reverts to per-message classification.")))
    }
}
