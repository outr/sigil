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
    """CALL THIS FIRST when the user asks you to DO something not in your current tool roster. Most
      |tools are discovered, not preloaded. Don't say something is unsupported without calling this.
      |
      |Matches are valid for ONE next turn — call the matched tool then, or it's cleared. If no
      |matches, you may tell the user it isn't available.
      |
      |`keywords` — space-separated lowercase terms; multi-word queries match better
      |(e.g. "send slack channel message" not just "slack").""".stripMargin,
  examples = List(
    ToolExample("Send a message",          FindCapabilityInput("send slack channel message")),
    ToolExample("Pause / wait / sleep",    FindCapabilityInput("sleep wait delay pause")),
    ToolExample("Look up by concept",      FindCapabilityInput("billing invoice payment charge"))
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
