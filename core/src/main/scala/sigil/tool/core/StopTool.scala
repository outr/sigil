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
    """Halt the current turn for a target agent (or every agent in the conversation when no target is
      |specified).
      |
      |`targetParticipantId` — the agent whose turn should stop. Omit (or pass null) to request that ALL
      |agents in the conversation stop; provide a specific participant id when you're only stopping one.
      |
      |`force`:
      |  - false (default): graceful — the current iteration finishes, but no more iterations start after
      |    it. Use when you just want the agent to yield so the user can redirect.
      |  - true: interrupt the in-flight streaming provider call immediately. Use for the monitor-agent
      |    pattern when you've spotted another agent about to take a destructive or clearly wrong action
      |    and need to cut its output now.
      |
      |`reason` is an optional short explanation displayed in UI and logs.""".stripMargin,
  examples = List(
    ToolExample(
      "Graceful stop of everyone in the conversation",
      StopInput()
    ),
    ToolExample(
      "Force stop with a reason (monitor-agent pattern)",
      StopInput(force = true, reason = Some("Peer is about to take a destructive action"))
    )
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
