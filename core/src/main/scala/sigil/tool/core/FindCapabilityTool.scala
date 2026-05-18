package sigil.tool.core

import sigil.TurnContext
import sigil.event.{CapabilityResults, Event}
import sigil.tool.{DiscoveryRequest, ToolExample, ToolName, TypedTool}

/**
 * Discovery tool. The agent calls `find_capability` when it needs to
 * check what capabilities exist to satisfy the current request.
 * Emits a [[CapabilityResults]] event carrying matches across every
 * category the framework surfaces (tools, modes, skills) so the LLM
 * has both the discovery (what exists) and the actionable next call
 * (`change_mode("…")` for a Mode, the tool name for a Tool) on its
 * next turn. Bug #66.
 */
case object FindCapabilityTool extends TypedTool[FindCapabilityInput](
  name = ToolName("find_capability"),
  description =
    """Search the capability catalog for a tool, mode, or skill that fits the user's task.
      |Call when no listed mode obviously matches (otherwise switch to a matching mode
      |first — modes are pre-curated and more precise than a free-form search).
      |
      |Returns matches across every capability kind:
      |  - Tools — call the matched name directly on your next turn.
      |  - Modes — match carries a hint for switching mode; switch to enter, then the
      |    mode's tools and skill become active. Prefer mode entry when a Mode matches
      |    the user's task — modes are designed end-to-end for their work shape.
      |
      |Matches are valid for ONE next turn — act on a match (call the tool, or switch
      |mode for a Mode match) then, or they're cleared. If the search truly returns
      |nothing, only THEN may you tell the user it isn't available.
      |
      |`keywords` — space-separated lowercase terms describing the action SHAPE (verb +
      |category), not project content. See the system prompt's "Discovery-query patterns"
      |section for template queries by intent.""".stripMargin,
  examples = List(
    ToolExample("Send a message",          FindCapabilityInput("send slack channel message")),
    ToolExample("Pause / wait / sleep",    FindCapabilityInput("sleep wait delay pause")),
    ToolExample("Look up by concept",      FindCapabilityInput("billing invoice payment charge"))
  )
) {
  override def paginate: Boolean = false

  // The discovery results are delivered into the caller's
  // ParticipantProjection.suggestedTools and rendered into the
  // system prompt's "Suggested tools" section — the verbose
  // ToolResults frame is redundant after the turn settles. Mark
  // it ephemeral so StandardContextCurator elides the pair from
  // future turns instead of letting it accumulate to a few
  // thousand tokens of stale schemas.
  override def resultTtl: Option[Int] = Some(0)

  override protected def executeTyped(input: FindCapabilityInput, context: TurnContext): rapid.Stream[Event] =
    rapid.Stream.force(
      context.sigil.accessibleSpaces(context.chain, context.conversation.id).flatMap { spaces =>
        val request = DiscoveryRequest(
          keywords = FindCapabilityTool.normaliseKeywords(input.keywords),
          chain = context.chain,
          mode = context.conversation.currentMode,
          callerSpaces = spaces,
          conversationId = Some(context.conversation.id)
        )
        context.sigil.findCapabilities(request).map { matches =>
          // Sigil bug #226 — record matches against the per-agent-loop
          // cache on the TurnContext. Replaces the prior projection
          // write path so the cache dies with the loop instead of
          // leaking into the next turn's system prompt.
          val toolNames = matches.collect {
            case m if m.capabilityType == sigil.tool.discovery.CapabilityType.Tool => sigil.tool.ToolName(m.name)
          }
          context.recordDiscovery(request.keywords, toolNames)
          val results = CapabilityResults(
            matches        = matches,
            participantId  = context.caller,
            conversationId = context.conversation.id,
            topicId        = context.conversation.currentTopicId,
            query          = request.keywords
          )
          rapid.Stream.emits(List(results))
        }
      }
    )

  /** Normalise a keywords string into the lowercase, space-separated
    * form `findTools` expects: drop punctuation, split snake_case /
    * camelCase / kebab-case, collapse runs to single spaces. */
  private[core] def normaliseKeywords(raw: String): String = {
    // Insert a space at every camelCase boundary BEFORE lowercasing,
    // so `getRandomDogImage` → `get Random Dog Image` → `get random dog image`.
    val withCamelSplit = raw.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
    withCamelSplit
      .toLowerCase
      .replaceAll("[^a-z0-9]+", " ")
      .trim
  }
}
