package sigil.tool.provider

import fabric.rw.*
import lightdb.time.Timestamp
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, TextToolOutput, Tool, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}

case class UnpinModelInput() extends ToolInput derives RW

/**
 * Clear the conversation's pinned model — dispatch reverts to the
 * normal stack (mode strategy → space strategy → agent's pinned
 * modelId fallback). Companion to [[PinModelTool]]. Not
 * auto-registered.
 */
case object UnpinModelTool extends Tool {
  type Input  = UnpinModelInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[UnpinModelInput]]
  val outputRW = summon[RW[TextToolOutput]]
  override val name = ToolName("unpin_model")
  override val description =
    """Clear the conversation's pinned model. Dispatch reverts to mode + space strategies and the
      |agent's pinned modelId fallback. No-op when nothing was pinned.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("unpin", "unlock", "clear", "auto", "default", "model"))
  )

  override def executeResult(input: UnpinModelInput,
                             ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    ctx.sigil.withDB(_.conversations.transaction(_.modify(ctx.conversation.id) {
      case None       => Task.pure(None)
      case Some(conv) => Task.pure(Some(conv.copy(pinnedModelId = None, modified = Timestamp())))
    })).map {
      case None =>
        ToolResult.failure(
          "Could not unpin model: conversation row not found. Try again from a live session.")
      case Some(_) =>
        ToolResult.Success(TextToolOutput(
          "Cleared the conversation's pinned model. Dispatch reverts to the normal strategy stack."))
    }
}
