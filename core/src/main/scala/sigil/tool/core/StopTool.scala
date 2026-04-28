package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, MessageRole, Stop}
import sigil.tool.{ToolExample, ToolName, TypedTool}
import sigil.tool.model.StopInput

/**
 * Halt the current turn for a target agent (or every agent in the
 * conversation when no target is specified).
 *
 * Atomic — emits a single Complete [[Stop]] event.
 */
case object StopTool extends TypedTool[StopInput](
  name = ToolName("stop"),
  description =
    """Halt the turn. Omit `targetParticipantId` to stop ALL agents. `force=true` interrupts an
      |in-flight call immediately (use for monitor-agent intercepts).""".stripMargin,
  examples = Nil
) {
  override protected def executeTyped(input: StopInput, context: TurnContext): rapid.Stream[Event] =
    rapid.Stream.emits(List(Stop(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      targetParticipantId = input.targetParticipantId,
      force = input.force,
      reason = input.reason,
      role = MessageRole.Tool
    )))
}
