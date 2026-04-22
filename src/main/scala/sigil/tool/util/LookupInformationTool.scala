package sigil.tool.util

import fabric.io.JsonFormatter
import fabric.rw.*
import sigil.TurnContext
import sigil.event.{Event, Message}
import sigil.information.Information
import sigil.information.Information.given
import sigil.tool.Tool
import sigil.tool.model.{LookupInformationInput, ResponseContent}

/**
 * Resolves an [[sigil.information.InformationSummary]] id to its full
 * [[Information]] record via [[sigil.Sigil.getInformation]] and returns
 * the result as a Message the agent can read on its next turn.
 *
 * The rendered content is the serialized `Information` JSON — apps with
 * opinionated rendering should wrap or replace this tool and format
 * content specifically for their subtypes.
 *
 * Atomic — emits a single Complete Message.
 */
object LookupInformationTool extends Tool[LookupInformationInput] {
  override protected def uniqueName: String = "lookup_information"

  override protected def description: String =
    """Resolve a previously-referenced Information id (from the "Referenced content" catalog)
      |to its full content. Call this when you need the details behind a catalog entry to answer
      |the user's request or to decide on a next action.""".stripMargin

  override def execute(input: LookupInformationInput, context: TurnContext): rapid.Stream[Event] =
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
