package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, ToolResults}
import sigil.signal.EventState
import sigil.tool.{Tool, ToolExample}

/**
 * Discovery tool. The agent calls `find_capability` when it needs to check
 * what tools exist to satisfy the current request — rather than guessing
 * or claiming inability to do something. Emits a [[ToolResults]] event
 * carrying the matching tools' schemas directly, so the LLM has everything
 * it needs to call one of them on its next turn.
 */
object FindCapabilityTool extends Tool[FindCapabilityInput] {
  override protected def uniqueName: String = "find_capability"

  override protected def description: String =
    """Discover tools available for the current request. Call this when the user asks for something and you
      |aren't sure whether a tool exists for it — do NOT guess or claim inability until you've checked.
      |
      |The `keywords` field is a space-separated list of lowercase alphanumeric keywords describing the
      |desired capability. Prefer multiple keywords — richer queries match better. Use "send slack channel
      |message" over just "slack"; use "database users count query" over just "database".
      |
      |Rules for `keywords`:
      |- lowercase letters and digits only
      |- single spaces between words (no leading, trailing, or multiple spaces)
      |- no punctuation, quotes, or special characters
      |
      |The response lists matching tools with their full schemas; you can then call the right one on your
      |next turn.""".stripMargin

  override protected def examples: List[ToolExample[FindCapabilityInput]] = List(
    ToolExample("Direct capability lookup", FindCapabilityInput("send slack channel message")),
    ToolExample("Concept search", FindCapabilityInput("billing invoice payment charge"))
  )

  override def execute(input: FindCapabilityInput, context: TurnContext): rapid.Stream[Event] =
    rapid.Stream.force(
      context.sigil
        .findTools(input.keywords, context.chain)
        .map { tools =>
          val results = ToolResults(
            schemas = tools.map(_.schema),
            participantId = context.caller,
            conversationId = context.conversation.id,
            state = EventState.Complete
          )
          rapid.Stream.emits(List(results))
        }
    )
}
