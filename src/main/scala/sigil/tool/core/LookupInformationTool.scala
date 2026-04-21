package sigil.tool.core

import fabric.io.JsonFormatter
import fabric.rw.*
import sigil.TurnContext
import sigil.event.{Event, Message}
import sigil.information.FullInformation
import sigil.information.FullInformation.given
import sigil.signal.EventState
import sigil.tool.Tool
import sigil.tool.model.{LookupInformationInput, ResponseContent}

/**
 * Resolves an [[sigil.information.Information]] id to its full content via
 * [[sigil.Sigil.getInformation]] and returns the result as a Message the
 * agent can read on its next turn.
 *
 * The rendered content is the serialized `FullInformation` JSON — apps
 * with opinionated rendering should wrap or replace this tool and format
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
          val body = JsonFormatter.Default(summon[RW[FullInformation]].read(full))
          rapid.Stream.emits(List(
            Message(
              participantId = context.caller,
              conversationId = context.conversation.id,
              content = Vector(ResponseContent.Text(body)),
              state = EventState.Complete
            )
          ))
        case None =>
          rapid.Stream.emits(List(
            Message(
              participantId = context.caller,
              conversationId = context.conversation.id,
              content = Vector(ResponseContent.Text(s"No Information found for id '${input.id.value}'.")),
              state = EventState.Complete
            )
          ))
      }
    )
}
