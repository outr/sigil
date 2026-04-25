package sigil.tool.util

import fabric.io.JsonFormatter
import fabric.rw.*
import sigil.TurnContext
import sigil.event.{Event, Message}
import sigil.information.Information
import sigil.information.Information.given
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.{LookupInformationInput, ResponseContent}

/**
 * Resolves an [[sigil.information.InformationSummary]] id to its full
 * [[Information]] record via [[sigil.Sigil.getInformation]] and returns
 * the result as a Message the agent can read on its next turn.
 */
case object LookupInformationTool extends TypedTool[LookupInformationInput](
  name = ToolName("lookup_information"),
  description =
    """Resolve a previously-referenced Information id (from the "Referenced content" catalog)
      |to its full content. Call this when you need the details behind a catalog entry to answer
      |the user's request or to decide on a next action.""".stripMargin,
  keywords = Set("information", "lookup", "resolve", "fetch")
) {
  override protected def executeTyped(input: LookupInformationInput, context: TurnContext): rapid.Stream[Event] =
    rapid.Stream.force(
      context.sigil.getInformation(input.id).map {
        case Some(full) =>
          val body = JsonFormatter.Default(summon[RW[Information]].read(full))
          rapid.Stream.emits(List(
            Message(
              participantId = context.caller,
              conversationId = context.conversation.id,
              topicId = context.conversation.currentTopicId,
              content = Vector(ResponseContent.Text(body))
            )
          ))
        case None =>
          rapid.Stream.emits(List(
            Message(
              participantId = context.caller,
              conversationId = context.conversation.id,
              topicId = context.conversation.currentTopicId,
              content = Vector(ResponseContent.Text(s"No Information found for id '${input.id.value}'."))
            )
          ))
      }
    )
}
