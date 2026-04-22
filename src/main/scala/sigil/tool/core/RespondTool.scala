package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, Message}
import sigil.tool.{Tool, ToolExample}
import sigil.tool.model.{MultipartParser, RespondInput, TopicChangeType}

/**
 * The respond tool. The model calls this to send its response to the user.
 *
 * The tool's `content` field is a string in the multipart format described in
 * the system prompt — each block headed by a `▶<TYPE>` marker. The string is
 * parsed into typed content blocks via [[MultipartParser]], then emitted as a
 * single [[Message]] event. Terminal — a turn that emits respond is considered
 * complete.
 *
 * `topic` + `topicConfidence` thread-labeling is applied by the orchestrator
 * at `ToolCallComplete` time, not here — resolving a switch vs. rename
 * requires DB access to the current [[sigil.conversation.Topic]] record,
 * which tools don't perform directly. This tool just emits the [[Message]].
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
      |`topic` — REQUIRED. The label of the current conversation thread. A concise 3–6 word phrase.
      |No quotes or punctuation. Pass the Current topic (shown at the top of the system prompt) unchanged
      |when `topicChangeType = NoChange`; propose a fresh label for Change and Update.
      |
      |`topicChangeType` — REQUIRED. Pick exactly one:
      |  - "Change"   — the user has moved to a DIFFERENT subject than the Current topic. Also use Change
      |    when the Current topic is "New Conversation" (the default bootstrap label) and the user has
      |    given you any real subject.
      |  - "Update"   — same subject as the Current topic, but the current label is vague or doesn't yet
      |    reflect the specific angle the user is asking about. Propose a more precise `topic`.
      |  - "NoChange" — the Current topic label still fits. Pass it unchanged in `topic`.
      |
      |Examples:
      |  Current topic = "New Conversation", user asks about Rome → Change, topic="Roman Empire".
      |  Current topic = "Python Programming", user asks specifically about the GIL → Update, topic="Python GIL".
      |  Current topic = "Python GIL", user asks a follow-up about the GIL → NoChange, topic="Python GIL".""".stripMargin

  override protected def examples: List[ToolExample[RespondInput]] = List(
    ToolExample(
      "Bootstrap — Current topic is the default, user has given a real subject (Change)",
      RespondInput(
        topic = "Roman Empire",
        content = "▶Text\nRome was founded in 753 BCE by Romulus.\n",
        topicChangeType = TopicChangeType.Change
      )
    ),
    ToolExample(
      "Refinement — same subject, sharper label (Update)",
      RespondInput(
        topic = "Python GIL",
        content = "▶Text\nThe GIL serializes bytecode execution across threads.\n",
        topicChangeType = TopicChangeType.Update
      )
    ),
    ToolExample(
      "Follow-up under the current topic (NoChange)",
      RespondInput(
        topic = "Python GIL",
        content = "▶Text\nIt's less of a problem for I/O-bound code.\n",
        topicChangeType = TopicChangeType.NoChange
      )
    ),
    ToolExample(
      "Hard switch to an unrelated subject (Change)",
      RespondInput(
        topic = "Database Migration Strategy",
        content = "▶Text\nOk, moving on to the migration question — here's the approach.\n",
        topicChangeType = TopicChangeType.Change
      )
    )
  )

  override def execute(input: RespondInput, context: TurnContext): rapid.Stream[Event] = {
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
