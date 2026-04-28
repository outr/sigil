package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, Message}
import sigil.signal.EventState
import sigil.tool.{ToolExample, ToolName, TypedTool}
import sigil.tool.model.{RespondFieldInput, ResponseContent}

/**
 * Emit a labeled key/value field as part of the agent's reply. Use for
 * compact card-shaped content (status summaries, news metadata, product
 * attributes) where the renderer needs label/value/icon structure that
 * markdown's bold-and-text can't express.
 */
case object RespondFieldTool extends TypedTool[RespondFieldInput](
  name = ToolName("respond_field"),
  description =
    """Emit a labeled key/value field — for compact metadata (status, source, timestamp). `icon`
      |is an optional semantic hint.""".stripMargin,
  examples = Nil
) {
  override protected def executeTyped(input: RespondFieldInput, context: TurnContext): rapid.Stream[Event] = {
    val block = ResponseContent.Field(label = input.label, value = input.value, icon = input.icon)
    rapid.Stream.emits(List(Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(block),
      state = EventState.Complete
    )))
  }
}
