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
    """CALL THIS FIRST when the user asks you to DO something and the action isn't obviously covered by the
      |tools already in your current roster. Most tools in this system are NOT in your default roster — they
      |are discovered through this call. Before telling the user something is impossible, unavailable, or
      |unsupported, you MUST call `find_capability`.
      |
      |Rules:
      |- NEVER say "I can't do that" or "that's not supported" without first calling `find_capability` with
      |  reasonable keywords derived from the user's request.
      |- When `find_capability` returns matching tools, CALL the most appropriate one immediately on your next
      |  turn — do not just describe what you found to the user.
      |- If `find_capability` returns no matches, THEN you may tell the user the capability isn't available.
      |- The only tools callable directly are those visible in your current tool roster. Everything else is
      |  reached via `find_capability` first.
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
      |The response lists matching tools with their full schemas; you then call the right one on your next
      |turn. Tools surfaced by `find_capability` are available for ONE subsequent turn — if you don't call
      |them next, they're cleared.""".stripMargin,
  examples = List(
    ToolExample(
      "User asked to send a message on some channel — not in default roster, discover it",
      FindCapabilityInput("send slack channel message")
    ),
    ToolExample(
      "User asked to wait or pause — discover timing / delay tools",
      FindCapabilityInput("sleep wait delay pause")
    ),
    ToolExample(
      "Concept search — map the user's request to relevant keywords",
      FindCapabilityInput("billing invoice payment charge")
    )
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
