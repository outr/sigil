package sigil.tool.core

import fabric.rw.*
import rapid.Task
import sigil.event.CapabilityResults
import sigil.signal.EventState
import sigil.tool.ToolContext
import sigil.tool.{DiscoveryRequest, Tool, ToolExample, ToolName, ToolResult}

/**
 * Discovery tool. The agent calls `find_capability` when it needs to
 * check what capabilities exist to satisfy the current request.
 * Resolves a [[FindCapabilityOutput]] carrying matches across every
 * category the framework surfaces (tools, modes, skills) so the LLM
 * has both the discovery (what exists) and the actionable next call
 * (`change_mode("…")` for a Mode, the tool name for a Tool) on its
 * next turn. Bug #66.
 */
case object FindCapabilityTool extends Tool {
  type Input  = FindCapabilityInput
  type Output = FindCapabilityOutput
  val inputRW  = summon[RW[FindCapabilityInput]]
  val outputRW = summon[RW[FindCapabilityOutput]]

  val name = ToolName("find_capability")
  val description =
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
      |section for template queries by intent.""".stripMargin

  override val examples: List[ToolExample] = List(
    ToolExample("Send a message",          FindCapabilityInput("send slack channel message")),
    ToolExample("Pause / wait / sleep",    FindCapabilityInput("sleep wait delay pause")),
    ToolExample("Look up by concept",      FindCapabilityInput("billing invoice payment charge"))
  )

  // The discovery results are delivered into the caller's
  // ParticipantProjection.suggestedTools and rendered into the
  // system prompt's "Suggested tools" section — the verbose
  // ToolResults frame is redundant after the turn settles. Mark
  // it ephemeral so StandardContextCurator elides the pair from
  // future turns instead of letting it accumulate to a few
  // thousand tokens of stale schemas.
  override def resultTtl: Option[Int] = Some(0)

  override def executeResult(input: FindCapabilityInput,
                             context: ToolContext): Task[ToolResult[FindCapabilityOutput]] =
    context.sigil.accessibleSpaces(context.chain, context.conversation.id).flatMap { spaces =>
      val request = DiscoveryRequest(
        keywords = FindCapabilityTool.normaliseKeywords(input.keywords),
        chain = context.chain,
        mode = context.conversation.currentMode,
        callerSpaces = spaces,
        conversationId = Some(context.conversation.id)
      )
      context.sigil.findCapabilities(request).flatMap { matches =>
        val toolNames = matches.collect {
          case m if m.capabilityType == sigil.tool.discovery.CapabilityType.Tool => sigil.tool.ToolName(m.name)
        }
        // Two-layer persistence (sigil #301):
        //   - Per-loop TurnContext cache drives the "Capabilities
        //     you've already discovered (this turn)" prompt section
        //     so subsequent iterations within ONE loop don't repeat
        //     the same query.
        //   - CapabilityResults event routes through Sigil.publish
        //     into projection.suggestedTools, surviving turn
        //     boundaries until the next find_capability call REPLACES
        //     the overlay. This is what gives a multi-turn task (user
        //     asks → agent searches → respond_options clarify → user
        //     answers → agent acts) access to the discovered roster
        //     on every turn, not just the discovery turn.
        context.turn.recordDiscovery(request.keywords, toolNames)
        val cr = CapabilityResults(
          matches        = matches,
          participantId  = context.caller,
          conversationId = context.conversation.id,
          topicId        = context.conversation.currentTopicId,
          query          = request.keywords,
          state          = EventState.Complete,
          origin         = Some(context.invokeId)
        )
        val hints = sigil.tool.discovery.TaskShapeHints.synthesize(request.keywords, matches)
        context.emit(cr).map { _ =>
          ToolResult.Success(FindCapabilityOutput(
            query          = request.keywords,
            matches        = matches,
            taskShapeHints = hints
          ))
        }
      }
    }

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
