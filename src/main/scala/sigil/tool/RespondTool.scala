package sigil.tool

import sigil.conversation.Conversation
import sigil.event.{Event, Message}
import sigil.participant.ParticipantId
import sigil.tool.model.{MultipartParser, RespondInput}

/**
 * The respond tool. The model calls this to send its response to the user.
 *
 * The tool's `content` field is a string in the multipart format described in
 * the system prompt — each block headed by a `▶<TYPE>` marker. The string is
 * parsed into typed content blocks via [[MultipartParser]], then emitted as a
 * single [[Message]] event. Terminal — a turn that emits respond is considered
 * complete.
 */
object RespondTool extends Tool[RespondInput] {
  override protected def uniqueName: String = "respond"

  override protected def description: String =
    """Send your response to the user.
      |
      |The `content` field is a multipart string. Each block begins with a header line and continues until the
      |next header or end of input — there are no close markers.
      |
      |Header format: ▶<TYPE>  or  ▶<TYPE> <arg>
      |
      |Available types:
      |  ▶Text         — plain text content
      |  ▶Markdown     — formatted prose with markdown syntax
      |  ▶Code <lang>  — source code in the named language (lang follows Code on the header line)
      |
      |Rules:
      |- The content string MUST start with a header. No preamble before the first ▶.
      |- Headers MUST be on their own line. Never run a header into content.
      |  WRONG: "▶Text Hello"   RIGHT: "▶Text\nHello"
      |- Each subsequent ▶ header IMPLICITLY ends the previous block. Do not emit empty blocks.
      |- Use \n for line breaks within the JSON string.
      |- Pick the most specific type for each block. Use Markdown only when no other type fits.""".stripMargin

  override protected def examples: List[ToolExample[RespondInput]] = List(
    ToolExample(
      "Mixed prose and code",
      RespondInput("▶Text\nHere's how to parse JSON in Scala:\n▶Code scala\nJsonParser(str)\n")
    ),
    ToolExample(
      "Single text reply",
      RespondInput("▶Text\nThe answer is 4.\n")
    )
  )

  override def execute(input: RespondInput, caller: ParticipantId, conversation: Conversation): rapid.Stream[Event] = {
    val blocks = MultipartParser.parse(input.content)
    val message = Message(participantId = caller, content = blocks)
    rapid.Stream.emits(List(message))
  }
}
