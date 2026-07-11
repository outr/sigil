package sigil.tool.provider

import fabric.rw.*
import lightdb.time.Timestamp
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

case class UnpinEffortInput() extends ToolInput derives RW

/**
 * Clear the conversation's pinned reasoning effort — the agent turn
 * reverts to the resolved candidate's own [[sigil.provider.GenerationSettings]].
 * Companion to [[PinEffortTool]]. Not auto-registered.
 */
case object UnpinEffortTool extends Tool {
  type Input = UnpinEffortInput
  type Output = TextToolOutput
  val inputRW = summon[RW[UnpinEffortInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("unpin_effort")
  val description =
    """Clear the conversation's pinned reasoning effort. The agent turn reverts to the resolved
      |model's own generation settings. No-op when nothing was pinned.""".stripMargin
  override val keywords = Set("unpin", "unlock", "clear", "auto", "default", "effort", "reasoning", "thinking")

  override def executeResult(input: UnpinEffortInput,
                             ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    ctx.sigil.withDB(_.conversations.transaction(_.modify(ctx.conversation.id) {
      case None => Task.pure(None)
      case Some(conv) => Task.pure(Some(conv.copy(pinnedEffort = None, modified = Timestamp())))
    })).map {
      case None =>
        ToolResult.failure(
          "Could not unpin effort: conversation row not found. Try again from a live session.")
      case Some(_) =>
        ToolResult.Success(TextToolOutput(
          "Cleared the conversation's pinned reasoning effort. The agent turn reverts to the resolved model's settings."))
    }
}
