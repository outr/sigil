package sigil.tool.core

import fabric.rw.*
import rapid.Task
import sigil.event.CapabilityResults
import sigil.signal.EventState
import sigil.tool.ToolContext
import sigil.tool.{DiscoveryRequest, DiscoverySpec, Effect, Freshness, OutputBounds, Resolution, Tool, ToolExample, ToolIO, ToolName, ToolProfile, ToolResult, ToolSpec}

/**
   * Discovery tool. The agent calls `find_capability` when it needs to
   * check what capabilities exist to satisfy the current request.
   * Resolves a [[FindCapabilityOutput]] carrying matches across every
   * category the framework surfaces (tools, modes, skills) so the LLM
   * has both the discovery (what exists) and the actionable next call
   * (`change_mode("…")` for a Mode, the tool name for a Tool) on its
   * next turn.
   */
case object FindCapabilityTool extends Tool {
  type Input  = FindCapabilityInput
  type Output = FindCapabilityOutput
  val io: ToolIO[FindCapabilityInput, FindCapabilityOutput] = ToolIO.derived[FindCapabilityInput, FindCapabilityOutput].withExamples(
    ToolExample("Send a message",          FindCapabilityInput("send slack channel message")),
    ToolExample("Pause / wait / sleep",    FindCapabilityInput("sleep wait delay pause")),
    ToolExample("Look up by concept",      FindCapabilityInput("billing invoice payment charge"))
  )

  override val name = ToolName("find_capability")
  override val description: String =
    """Search the capability catalog for a tool, mode, or skill that fits the user's task.
      |CALL THIS FIRST whenever the user asks for an action and nothing in your current
      |roster obviously covers it — discovery is how you reach the full catalog, which is
      |far larger than your visible roster. (When a listed mode obviously matches, switch
      |to it first — modes are pre-curated and more precise than a free-form search.)
      |
      |Returns matches across every capability kind:
      |  - Tools — call the matched name directly on your next turn.
      |  - Modes — the match carries a hint for switching mode; switch to enter, then the
      |    mode's tools and skill become active. Prefer mode entry when a Mode matches
      |    the user's task — modes are designed end-to-end for their work shape.
      |
      |Matches are valid for ONE next turn — act on a match (call the tool, or switch
      |mode for a Mode match) then, or they're cleared. If the search truly returns
      |nothing, only THEN may you tell the user it isn't available.
      |
      |`keywords` — space-separated lowercase terms describing the action SHAPE (verb +
      |category), not project content. This is a TOOL-SHAPE search, not a CONTENT search:
      |strip filenames, project terms, and business jargon; keep the shape. 3-5 words match
      |better than one. Templates by intent:
      |  - Read a file → `view file source contents read code lines`
      |  - Search files → `grep search find text pattern match`
      |  - List paths → `glob files directory paths list discover`
      |  - Run shell → `bash shell command execute run`
      |  - Navigate code symbols → `lsp definition reference symbol type implementation`
      |  - Edit a file → `edit modify update file patch change`
      |  - HTTP fetch → `http fetch download url web request`
      |  - Switch the model → `model switch pin change llm`
      |  - Save / recall memory → `memory save recall persist note remember`
      |  - Schedule / wait → `sleep wait delay timer schedule cron`
      |Bad query: `find references search symbol password reset` (mixes shape with project
      |content). Good: `lsp reference symbol definition` (pure shape — what the ranker scores).
      |
      |Results are RANKED by relevance — the top match is the framework's recommendation, not
      |a buffet to scroll. Default to the rank-1 result unless its description is clearly
      |inappropriate; don't scroll past a domain-specific match to a generic primitive just
      |because it's more familiar. If a capability you used earlier isn't in your current
      |roster, re-run this search to recover it — that's the intended recovery path.""".stripMargin

  // ReadOnly(Volatile): the roster answer depends on live DB tool
  // records, the active mode, and per-turn overlays — never cached,
  // and the curator elides settled result frames (the discovery
  // results live on in `suggestedTools` / the system prompt, so the
  // verbose frame is redundant after the turn settles).
  // SelfBounded: the curated roster is sized to the model window in
  // `sizeToModel` and must arrive intact — never truncated mid-entry
  // or spilled to a file.
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(
      effect = Effect.ReadOnly(Freshness.Volatile),
      output = OutputBounds.SelfBounded
    ),
    discovery = DiscoverySpec(keywords = Set("find", "capability", "discover", "search", "catalog", "tool"))
  )


  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: FindCapabilityInput,
                            context: ToolContext): Task[ToolResult[FindCapabilityOutput]] =
    context.sigil.accessibleSpaces(context.chain, context.conversation.id).flatMap { spaces =>
      val request = DiscoveryRequest(
        keywords = FindCapabilityTool.normaliseKeywords(input.keywords),
        chain = context.chain,
        mode = context.conversation.currentMode,
        callerSpaces = spaces,
        conversationId = Some(context.conversation.id)
      )
      context.sigil.findCapabilities(request).flatMap { allMatches =>
        // Sigil #347 — size the roster to the running model's context
        // window (count + rendered-bytes budget), trimming lowest-scored
        // matches first, so a small-context model gets a roster that fits
        // with room to act instead of one that overflows and gets chopped.
        val matches = FindCapabilityTool.sizeToModel(allMatches, context.turn.model.contextLength)
        val toolNames = matches.collect {
          case m if m.capabilityType == sigil.tool.discovery.CapabilityType.Tool => sigil.tool.ToolName.internal(m.name)
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

  /** Sigil #347 — trim a score-sorted match list to what the running
    * model's context window can hold with room to act: a rendered-bytes
    * budget (~15% of the window at ~4 chars/token) and a count cap that
    * scales with the window (3 on a tiny model, up to 25 on a large
    * one). Lowest-scored matches are dropped first; at least one match
    * always survives. The input must already be sorted by score desc. */
  private[core] def sizeToModel(matches: List[sigil.tool.discovery.CapabilityMatch],
                                contextLength: Long): List[sigil.tool.discovery.CapabilityMatch] = {
    val budgetChars = math.max(1500, (contextLength.toDouble * 4.0 * 0.15).toInt)
    val maxCount    = math.max(3, math.min(25, (contextLength / 8000L).toInt))
    val out = scala.collection.mutable.ListBuffer.empty[sigil.tool.discovery.CapabilityMatch]
    var used = 0
    var stopped = false
    matches.foreach { m =>
      if (!stopped) {
        val cost = m.name.length + m.description.length + 8
        if (out.size >= maxCount) stopped = true
        else if (used + cost > budgetChars && out.nonEmpty) stopped = true
        else { out += m; used += cost }
      }
    }
    out.toList
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
