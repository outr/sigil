package sigil.tool.memory

import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message}
import sigil.tool.model.ResponseContent
import sigil.tool.{ToolExample, ToolName, TypedTool}

/**
 * Opt-in tool: agent-driven hard-delete of a keyed memory (all versions).
 */
case object ForgetTool extends TypedTool[ForgetInput](
  name = ToolName("forget"),
  description =
    """Hard-delete every version of a keyed memory. Irreversible — use when you're sure
      |the fact is no longer true or relevant and no history is needed.
      |
      |`key`     — the memory key to remove.
      |`spaceId` — optional; omit to use the caller's default scope.""".stripMargin,
  examples = List(
    ToolExample("Forget the user's old theme preference", ForgetInput(key = "user.ui.theme"))
  ),
  keywords = Set("forget", "delete", "remove", "memory")
) {
  override protected def executeTyped(input: ForgetInput, context: TurnContext): Stream[Event] =
    Stream.force {
      resolveSpace(input, context).flatMap {
        case None =>
          Task.pure(toMsg(context,
            s"[forget] no memory space available for key ${input.key}. Provide spaceId or wire Sigil.defaultMemorySpace."))
        case Some(space) =>
          context.sigil.forgetMemory(input.key, space).map { removed =>
            toMsg(context,
              if (removed == 0) s"[forget] no memories matched key ${input.key}"
              else s"[forget] removed $removed version(s) of key ${input.key}")
          }
      }.map(msg => Stream.emits(List[Event](msg)))
    }

  private def resolveSpace(input: ForgetInput, context: TurnContext) =
    input.spaceId match {
      case Some(s) => Task.pure(Some(s))
      case None    => context.sigil.defaultMemorySpace(context.conversation.id)
    }

  private def toMsg(context: TurnContext, body: String): Message =
    Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(ResponseContent.Text(body))
    )
}
