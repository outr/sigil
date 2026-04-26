package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, ModeChange, Role}
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
    """Switch the agent's current operating mode. The current mode is stated at the top of the system
      |prompt along with the list of modes available in this conversation. Call this BEFORE attempting
      |a task whose nature belongs to a different mode — do not start the task in the wrong mode and
      |then switch.
      |
      |Typical example: the current mode is conversational and the user asks you to write a Scala
      |function; call change_mode to the coding-style mode first, then address the request on the
      |next turn.
      |
      |The `mode` argument is the target mode's stable name as shown in the system prompt's mode
      |listing (e.g. "conversation", "coding", "workflow" — whatever this conversation's registered
      |mode set contains). Unknown names are rejected.""".stripMargin
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
            role = Role.Tool
          )
        ))
      case None =>
        scribe.warn(s"change_mode called with unknown mode name: ${input.mode}")
        rapid.Stream.empty
    }
}
