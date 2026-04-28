package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, Message}
import sigil.signal.EventState
import sigil.tool.{ToolExample, ToolName, TypedTool}
import sigil.tool.model.{RespondOptionsInput, ResponseContent, SelectOption}

/**
 * Emit a structured multiple-choice block as part of the agent's reply.
 * Markdown can't natively express interactive choices, so this is one of
 * the small set of atomic content tools that complement the plain
 * markdown content stream.
 *
 * Emits a fresh Complete `Message` carrying the Options block as its
 * single content entry. Multi-block replies that mix markdown with
 * structured Options will produce multiple Message events on the wire;
 * each renders as its own UI bubble / card.
 */
case object RespondOptionsTool extends TypedTool[RespondOptionsInput](
  name = ToolName("respond_options"),
  description =
    """Offer the user a fixed set of selectable choices. The user may also answer in natural language.
      |
      |- `allowMultiple` — false = exactly one; true = zero or more.
      |- An `exclusive` option (multi-select only) cannot be combined with others (e.g. "None").""".stripMargin,
  examples = Nil
) {
  override protected def executeTyped(input: RespondOptionsInput, context: TurnContext): rapid.Stream[Event] = {
    val block = ResponseContent.Options(
      prompt = input.prompt,
      options = input.options,
      allowMultiple = input.allowMultiple
    )
    rapid.Stream.emits(List(Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = Vector(block),
      state = EventState.Complete
    )))
  }
}
