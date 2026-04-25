package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, Message}
import sigil.tool.{ToolExample, ToolName, TypedTool}
import sigil.tool.model.{MultipartParser, RespondInput}

/**
 * The respond tool. The model calls this to send its response to the user.
 *
 * Topic-shift resolution (new / refine / return / no-change) is handled by
 * the orchestrator at `ToolCallComplete` time via a focused two-step
 * classifier — see [[sigil.Sigil.classifyTopicShift]].
 */
case object RespondTool extends TypedTool[RespondInput](
  name = ToolName("respond"),
  description =
    """ALWAYS use this tool to reply to the user. Plain text output is dropped by the framework — every
      |user-facing message MUST go through `respond`. Describe what you did in natural language AFTER an
      |action through this tool; do not narrate tool calls before or as you make them.
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
      |`topicLabel` — REQUIRED. A concise 3-6 word label describing the subject of THIS turn.
      |No quotes, no punctuation. Examples:
      |  - On a fresh "New Conversation" with the user asking about Rome → "Roman Empire"
      |  - When the user narrows from general Python to GIL specifically → "Python GIL"
      |  - When following up on the same subject → keep the Current topic label unchanged
      |  - When the user explicitly returns to a Previous topic → use that prior label exactly
      |
      |`topicSummary` — REQUIRED. A 1-2 sentence summary of the subject. Used both as UI display and
      |as semantic context the framework's classifier uses to compare against prior topics.""".stripMargin,
  examples = List(
    ToolExample(
      "Bootstrap — Current topic is the default, user has given a real subject",
      RespondInput(
        topicLabel = "Roman Empire",
        topicSummary = "Discussion of the founding and history of the Roman Empire.",
        content = "▶Text\nRome was founded in 753 BCE by Romulus.\n"
      )
    ),
    ToolExample(
      "Refinement — Current topic is Python Programming, user asked about GIL",
      RespondInput(
        topicLabel = "Python GIL",
        topicSummary = "Python's Global Interpreter Lock and its effect on threading.",
        content = "▶Text\nThe GIL serializes bytecode execution across threads.\n"
      )
    ),
    ToolExample(
      "Follow-up under the current topic — keep label and summary aligned with what's discussed",
      RespondInput(
        topicLabel = "Python GIL",
        topicSummary = "Python's GIL — current discussion focuses on its impact on I/O-bound code.",
        content = "▶Text\nIt's less of a problem for I/O-bound code.\n"
      )
    ),
    ToolExample(
      "Hard switch to an unrelated subject",
      RespondInput(
        topicLabel = "Database Migration Strategy",
        topicSummary = "Strategy for migrating between database systems while minimizing downtime.",
        content = "▶Text\nOk, moving on to the migration question — here's the approach.\n"
      )
    )
  )
) {
  override protected def executeTyped(input: RespondInput, context: TurnContext): rapid.Stream[Event] = {
    val blocks = MultipartParser.parse(input.content)
    val message = Message(
      participantId = context.caller,
      conversationId = context.conversation.id,
      topicId = context.conversation.currentTopicId,
      content = blocks
    )
    rapid.Stream.emits(List[Event](message))
  }
}
