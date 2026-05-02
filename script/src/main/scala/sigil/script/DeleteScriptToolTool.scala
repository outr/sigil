package sigil.script

import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageVisibility, MessageRole}
import sigil.signal.EventState
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.ResponseContent

/**
 * Remove an existing [[ScriptTool]]. Looks the record up by `name`;
 * rejects if the caller's [[sigil.Sigil.accessibleSpaces]] doesn't
 * include the tool's `space` (or the space isn't
 * [[sigil.GlobalSpace]]). No suggestion cascade — once a tool is
 * deleted there's nothing to act on.
 */
case object DeleteScriptToolTool extends TypedTool[DeleteScriptToolInput](
  name = ToolName("delete_script_tool"),
  description =
    """Remove a previously created script-backed tool. Identified by `name`. Permission is
      |denied if the caller doesn't have access to the tool's space.""".stripMargin,
  modes = Set(ScriptAuthoringMode.id),
  keywords = Set("delete", "remove", "tool", "script", "drop")
) {
  override protected def executeTyped(input: DeleteScriptToolInput,
                                      context: TurnContext): Stream[Event] = Stream.force(
    context.sigil.accessibleSpaces(context.chain).flatMap { accessible =>
      context.sigil.withDB(_.tools.transaction { tx =>
        tx.query.filter(_.toolName === input.name).toList.map(_.headOption).flatMap {
          case None =>
            Task.pure(reply(context, s"No script tool named '${input.name}' found."))
          case Some(existing: ScriptTool) =>
            if (existing.space != sigil.GlobalSpace && !accessible.contains(existing.space)) {
              Task.pure(reply(context, s"Tool '${input.name}' is not accessible to this caller."))
            } else {
              tx.delete(existing._id).map { _ =>
                reply(context, s"Deleted tool '${existing.name.value}'.")
              }
            }
          case Some(_) =>
            Task.pure(reply(context, s"Tool '${input.name}' exists but is not a script tool."))
        }
      })
    }.handleError { e =>
      Task.pure(reply(context, s"Failed to delete tool: ${e.getMessage}"))
    }
  )

  private def reply(context: TurnContext, text: String): Stream[Event] =
    Stream.emit(Message(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      content        = Vector(ResponseContent.Text(text)),
      state          = EventState.Complete,
      role           = MessageRole.Tool,
      visibility     = MessageVisibility.Agents
    ))
}
