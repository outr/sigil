package sigil.tool

import sigil.conversation.Conversation
import sigil.event.{Event, Message, TitleChangedEvent}
import sigil.participant.ParticipantId
import sigil.tool.model.{RespondInput, ResponseContent}

/**
 * The respond tool. The model calls this to send its response to the user.
 *
 * Emits a Message with the structured content. If the input
 * includes a new title, also emits a TitleChangedEvent. Terminal — a turn
 * that emits respond is considered complete.
 */
object RespondTool extends Tool[RespondInput] {
  override protected def uniqueName: String = "respond"
  override protected def description: String =
    "Send your response to the user. Use the most specific content type for each part of your response. " +
    "Use 'markdown' only as a last resort when no other type fits."
  override protected def examples: List[ToolExample[RespondInput]] = List(
      ToolExample(
        "Mixed prose and code",
        RespondInput(Vector(
          ResponseContent.Text("Here's how to parse JSON in Scala:"),
          ResponseContent.Code("JsonParser(str)", Some("scala"))
        ))
      ),
      ToolExample(
        "Response with source citation",
        RespondInput(Vector(
          ResponseContent.Text("The tolerance for Part 42 is ±0.05mm."),
          ResponseContent.Citation("manual.pdf", Some("Section 3.2, page 17"), None)
        ))
      )
    )

  override def execute(
    input: RespondInput,
    caller: ParticipantId,
    conversation: Conversation
  ): rapid.Stream[Event] = {
    val message = Message(
      participantId = caller,
      content = input.content
    )
    val titleEvent = input.title.map(t => TitleChangedEvent(title = t))
    rapid.Stream.emits(message :: titleEvent.toList)
  }
}
