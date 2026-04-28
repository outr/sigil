package sigil.tool.core

import sigil.TurnContext
import sigil.event.{Event, ToolResults}
import sigil.tool.{DiscoveryRequest, ToolExample, ToolName, TypedTool}

/**
 * Discovery tool. The agent calls `find_capability` when it needs to check
 * what tools exist to satisfy the current request. Emits a [[ToolResults]]
 * event carrying the matching tools' schemas directly so the LLM has
 * everything it needs to call one of them on its next turn.
 */
case object FindCapabilityTool extends TypedTool[FindCapabilityInput](
  name = ToolName("find_capability"),
  description =
    """CALL THIS FIRST when the user asks you to DO something not obviously covered by your current tool
      |roster. Most tools live outside the default roster — discover them through this call before saying
      |anything is impossible or unsupported.
      |
      |- When matches are returned, CALL the most appropriate one on your next turn — don't just describe it.
      |- Tools surfaced here are available for ONE subsequent turn; uncalled, they're cleared.
      |- If no matches, THEN you may tell the user the capability isn't available.
      |
      |`keywords` — space-separated lowercase alphanumeric terms (no punctuation). Prefer multiple terms:
      |"send slack channel message" over "slack"; "database users count query" over "database".""".stripMargin,
  examples = List(
    ToolExample("Send a message on some channel", FindCapabilityInput("send slack channel message")),
    ToolExample("Wait or pause",                  FindCapabilityInput("sleep wait delay pause")),
    ToolExample("Concept search",                 FindCapabilityInput("billing invoice payment charge"))
  )
) {
  override protected def executeTyped(input: FindCapabilityInput, context: TurnContext): rapid.Stream[Event] =
    rapid.Stream.force(
      context.sigil.accessibleSpaces(context.chain).flatMap { spaces =>
        val request = DiscoveryRequest(
          keywords = input.keywords,
          chain = context.chain,
          mode = context.conversation.currentMode,
          callerSpaces = spaces
        )
        context.sigil.findTools(request).map { tools =>
          val results = ToolResults(
            schemas = tools.map(_.schema),
            participantId = context.caller,
            conversationId = context.conversation.id,
            topicId = context.conversation.currentTopicId
          )
          rapid.Stream.emits(List(results))
        }
      }
    )
}
