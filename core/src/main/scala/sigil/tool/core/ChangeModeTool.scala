package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, ModeChange, MessageRole}
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.ChangeModeInput

/**
 * Allows the model to transition between operating modes mid-conversation.
 *
 * Emits a `ModeChange` that the framework uses to update
 * `Conversation.currentMode` and re-render the next provider request.
 */
case object ChangeModeTool extends TypedTool[ChangeModeInput](
  name = ToolName("change_mode"),
  description =
    """Switch operating mode. Call BEFORE starting a task that belongs to a different mode — e.g.
      |in conversation mode and the user asks for code → call change_mode("coding") first, then
      |code on the next turn. `mode` is the target's stable name from the system prompt's mode list.""".stripMargin
) {
  override protected def executeTyped(input: ChangeModeInput, context: TurnContext): rapid.Stream[Event] =
    context.sigil.modeByName(input.mode) match {
      case Some(mode) =>
        rapid.Stream.emits(List(
          ModeChange(
            mode = mode,
            reason = input.reason,
            participantId = context.caller,
            conversationId = context.conversation.id,
            topicId = context.conversation.currentTopicId,
            role = MessageRole.Tool
          )
        ))
      case None =>
        scribe.warn(s"change_mode called with unknown mode name: ${input.mode}")
        rapid.Stream.empty
    }
}
