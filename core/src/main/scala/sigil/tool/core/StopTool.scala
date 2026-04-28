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
    """Halt the current turn for a target agent (or every agent when no target is specified).
      |
      |- `targetParticipantId` — agent to stop. Omit/null to stop ALL agents in the conversation.
      |- `force` — false (default) lets the current iteration finish then halts; true interrupts the
      |  in-flight provider call immediately. Use `true` for monitor-agent patterns where a peer is
      |  about to do something destructive.
      |- `reason` — optional short explanation shown in UI and logs.""".stripMargin,
  examples = List(
    ToolExample("Graceful stop of all agents", StopInput()),
    ToolExample("Force stop with reason",       StopInput(force = true, reason = Some("Peer about to take destructive action")))
  )
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
