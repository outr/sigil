package sigil.tool.provider

import fabric.rw.*
import lightdb.time.Timestamp
import rapid.Task
import sigil.TurnContext
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}

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
  val name = ToolName("unpin_model")
  val description =
    """Clear the conversation's pinned model. Dispatch reverts to mode + space strategies and the
      |agent's pinned modelId fallback. No-op when nothing was pinned.""".stripMargin
  override val keywords = Set("unpin", "unlock", "clear", "auto", "default", "model")

  override def executeResult(input: UnpinModelInput,
                             ctx: TurnContext): Task[ToolResult[TextToolOutput]] =
    ctx.sigil.withDB(_.conversations.transaction(_.modify(ctx.conversation.id) {
      case None       => Task.pure(None)
      case Some(conv) => Task.pure(Some(conv.copy(pinnedModelId = None, modified = Timestamp())))
    })).map { _ =>
      ToolResult.Success(TextToolOutput(
        "Cleared the conversation's pinned model. Dispatch reverts to the normal strategy stack."))
    }
}
