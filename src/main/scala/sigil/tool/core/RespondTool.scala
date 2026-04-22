package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, Message, TitleChange}
import sigil.tool.{Tool, ToolExample}
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
    """Send your response to the user. Every user-facing reply goes through this call — you cannot emit
      |plain text to reach the user. Describe what you did in natural language AFTER an action through
      |this tool; do not narrate tool calls before or as you make them.
      |
      |`content` is a multipart string. Each block begins with a header line and continues until the
      |next header or end of input — there are no close markers.
      |
      |Header format: ▶<TYPE>  or  ▶<TYPE> <arg>
      |
      |Available types:
      |  ▶Text         — plain text
      |  ▶Markdown     — formatted prose with markdown syntax
      |  ▶Code <lang>  — source code in the named language (lang follows Code on the header line)
      |  ▶Heading      — section or card heading (single line of text)
      |  ▶Field        — labeled key/value (JSON body: {"label":"...","value":"...","icon":"..."})
      |  ▶Divider      — visual separator between sections (no body)
      |  ▶Options      — structured multiple-choice question; body is a JSON object
      |
      |Card-shaped content (news items, product previews, status summaries) is expressed as a flat sequence of
      |blocks — typically ▶Heading followed by ▶Field entries and a ▶Link. The renderer decides whether to
      |group them as a visual card, a chat card, or plain formatted text.
      |
      |Example — a news item:
      |▶Heading
      |Scala 4.0 Released
      |▶Field
      |{"label":"Source","value":"Scala Center","icon":"article"}
      |▶Field
      |{"label":"Published","value":"2026-03-14","icon":"clock"}
      |▶Text
      |Scala 4.0 brings refined macros, faster compilation, and improved cross-platform tooling.
      |
      |▶Options body is a JSON object:
      |  { "prompt": string,
      |    "allowMultiple": boolean (default false — only one option can be selected),
      |    "options": [ { "label": string,
      |                   "value": string,
      |                   "description": string?  (optional longer explanation),
      |                   "exclusive": boolean?  (default false; meaningful only when allowMultiple=true. An exclusive option cannot be combined with others — use for "None of the above", "All of these", etc.) } ] }
      |
      |The user is always free to reply with a natural-language message instead of picking from the options —
      |do not add a "write your own" choice or similar; just present the structured alternatives.
      |
      |Example — single select:
      |▶Options
      |{"prompt":"Region","options":[{"label":"US East","value":"us-east"},{"label":"EU West","value":"eu-west"}]}
      |
      |Example — multi-select with exclusive escape hatch:
      |▶Text
      |Which notification channels would you like?
      |▶Options
      |{"prompt":"Channels","allowMultiple":true,"options":[{"label":"Email","value":"email"},{"label":"SMS","value":"sms"},{"label":"None","value":"none","exclusive":true}]}
      |
      |Rules:
      |- The content string MUST start with a header. No preamble before the first ▶.
      |- Headers MUST be on their own line. Never run a header into content.
      |  WRONG: "▶Text Hello"   RIGHT: "▶Text\nHello"
      |- Each subsequent ▶ header IMPLICITLY ends the previous block. Do not emit empty blocks.
      |- Use \n for line breaks within the JSON string.
      |- Pick the most specific type for each block. Use Markdown only when no other type fits.
      |- When asking the user to choose from a fixed set of alternatives, PREFER ▶Options over a numbered prose list.
      |
      |`title` — REQUIRED on every call:
      |- If the current conversation title (shown at the top of the system prompt) still fits, pass it
      |  UNCHANGED. The framework detects the no-op and suppresses the title-change event.
      |- Propose a new concise 3-6 word title ONLY when:
      |    a) the current title is "New Conversation" (freshly-created conversation), or
      |    b) the topic has meaningfully shifted and the existing title no longer fits.
      |- No quotes, no punctuation in titles.""".stripMargin

  override protected def examples: List[ToolExample[RespondInput]] = List(
    ToolExample(
      "Mixed prose and code",
      RespondInput(title = "JSON Parsing in Scala", content = "▶Text\nHere's how to parse JSON in Scala:\n▶Code scala\nJsonParser(str)\n")
    ),
    ToolExample(
      "Single text reply",
      RespondInput(title = "Simple Arithmetic", content = "▶Text\nThe answer is 4.\n")
    )
  )

  override def execute(input: RespondInput, context: TurnContext): rapid.Stream[Event] = {
    val blocks = MultipartParser.parse(input.content)
    val message = Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      content = blocks
    )
    val titleChange =
      if (input.title.nonEmpty && input.title != context.conversation.title)
        Some(TitleChange(
          title = input.title,
          participantId = context.caller,
          conversationId = context.conversation.id
        ))
      else None

    val events: List[Event] = titleChange.toList ::: List(message)
    rapid.Stream.emits(events)
  }
}
