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
    """CALL THIS FIRST when the user asks you to DO something not in your current tool roster. Most
      |capabilities are discovered, not preloaded. Don't say something is unsupported without calling this.
      |
      |Returns matches across every kind of capability:
      |  - Tools — call the matched name directly on your next turn.
      |  - Modes — bundle a focused skill + scoped tool roster. The match carries a hint
      |    (`change_mode("name")`) — call THAT to enter the mode, then the mode's tools and
      |    skill become active. When a Mode appears in your matches and fits the user's task,
      |    prefer the mode-entry path; modes are designed end-to-end for their work shape.
      |
      |Matches are valid for ONE next turn — act on a match (call the tool, or call
      |change_mode for a Mode) then, or they're cleared. If no matches, you may tell the user
      |it isn't available.
      |
      |`keywords` — space-separated lowercase terms; multi-word queries match better
      |(e.g. "send slack channel message" not just "slack").""".stripMargin,
  examples = List(
    ToolExample("Send a message",          FindCapabilityInput("send slack channel message")),
    ToolExample("Pause / wait / sleep",    FindCapabilityInput("sleep wait delay pause")),
    ToolExample("Look up by concept",      FindCapabilityInput("billing invoice payment charge"))
  )
) {
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
      context.sigil.accessibleSpaces(context.chain).flatMap { spaces =>
        val request = DiscoveryRequest(
          // Bug #52 — normalise explicitly: lowercase, replace any
          // non-alphanumeric run with a single space, trim. The
          // schema used to enforce this via `@pattern`, but
          // grammar-constrained decoders don't compile pattern
          // regexes, and the validator-rejection loop on
          // snake_case identifiers (`get_random_dog_image` etc.)
          // had no recovery. Doing the normalisation at the tool
          // boundary lets the model emit any natural form.
          keywords = FindCapabilityTool.normaliseKeywords(input.keywords),
          chain = context.chain,
          mode = context.conversation.currentMode,
          callerSpaces = spaces
        )
        context.sigil.findCapabilities(request).map { matches =>
          val results = CapabilityResults(
            matches = matches,
            participantId = context.caller,
            conversationId = context.conversation.id,
            topicId = context.conversation.currentTopicId
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
