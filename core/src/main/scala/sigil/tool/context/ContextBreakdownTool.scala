package sigil.tool.context

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tokenize.HeuristicTokenizer
import sigil.diagnostics.{ProfileSection, RequestProfiler}
import sigil.participant.AgentParticipant
import sigil.provider.{ContextFeatures, ConversationRequest, GenerationSettings, Instructions, SectionContext}
import sigil.tool.model.{ContextBreakdownOutput, ContextSectionBreakdown}
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, Tool, ToolIO, ToolName, ToolProfile, ToolSpec}

/**
 * Returns a breakdown of how the current turn's context is being
 * spent — section-by-section token contributions. Used when the user
 * asks "where is your context going?" / "why is my context full?".
 *
 * The numbers come from [[sigil.diagnostics.RequestProfiler]] run over
 * a [[SectionContext]] built from the current turn — the same
 * accounting the wire profile reports, against the same section list
 * ([[sigil.Sigil.contextSections]]), so the agent's answer cannot drift
 * from what the framework actually sends. The wire-only pieces the tool
 * cannot see from a dispatch (the resolved tool roster and its framing
 * preamble) are outside the breakdown; the note says so.
 *
 * Token counts via the char/4 heuristic — accurate enough to answer the
 * user's question; production budget enforcement uses the provider's
 * per-vendor tokenizer separately.
 *
 * Emits a typed [[ContextBreakdownOutput]].
 */
case object ContextBreakdownTool extends Tool {
  type Input = ContextBreakdownInput
  type Output = ContextBreakdownOutput
  val io: ToolIO[ContextBreakdownInput, ContextBreakdownOutput] = ToolIO.derived[ContextBreakdownInput, ContextBreakdownOutput]
  override val name = ToolName("context_breakdown")
  override val description =
    """Return a section-by-section breakdown of where your context window is being spent
      |this turn — frames, critical memories, retrieved memories, active skills, etc.
      |Use this when the user asks "what's in your context?" / "why is my context full?".
      |
      |For details on specific pinned items, use the memory-listing and memory-unpinning
      |tools available in this conversation.""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Volatile)),
    discovery = DiscoverySpec(keywords = Set("context", "breakdown", "tokens", "usage", "share", "where", "why"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: ContextBreakdownInput, context: ToolContext): Task[ContextBreakdownOutput] = {
    val sigil = context.sigil
    val conv = context.conversation
    val agent = conv.participants.collectFirst {
      case a: AgentParticipant if a.id == context.caller => a
    }
    val rolesTask = agent.fold(Task.pure(List.empty[_root_.sigil.role.Role]))(_.resolveRoles(context.turn))
    for {
      resolved <- sigil.resolveReferences(context.turnInput)
      roles <- rolesTask
      // Features are computed here for the same reason the provider
      // computes them before rendering: the breakdown must account for
      // the bytes a real turn would carry, features included.
      sectionContext <- ContextFeatures.evaluate(
        sigil.enabledContextFeatures,
        SectionContext(
          request = ConversationRequest(
            conversationId = conv.id,
            model = context.model,
            instructions = agent.map(_.instructions).getOrElse(Instructions()),
            turnInput = context.turnInput,
            currentMode = conv.currentMode,
            currentTopic = conv.currentTopic,
            previousTopics = conv.previousTopics,
            generationSettings = GenerationSettings(),
            chain = context.chain,
            roles = roles
          ),
          resolved = resolved,
          discoveredCapabilitiesPromptCap = sigil.discoveredCapabilitiesPromptCap,
          promptShape = sigil.modelProfileFor(context.model).promptShape
        )
      )
    } yield {
      val profile = RequestProfiler.profile(sectionContext, HeuristicTokenizer, sigil, sigil.resolvedContextSections)
      val sections = profile.sections.toList
        .sortBy { case (_, tokens) => -tokens }
        .map { case (id, tokens) => ContextSectionBreakdown(id, tokens, entryCount(id, sectionContext)) }
      ContextBreakdownOutput(
        totalTokens = profile.total,
        currentMode = conv.currentMode.name,
        sections = sections,
        note = "Section accounting is the framework's own wire profiler, minus the tool roster and its " +
          "framing preamble (not visible from a tool dispatch). Tokens estimated via the char/4 heuristic; " +
          "production budget uses the provider's tokenizer."
      )
    }
  }

  /**
   * How many entries a section rendered, for the list-shaped ones;
   * `1` for the sections that render a single block.
   */
  private def entryCount(id: ProfileSection, c: SectionContext): Int = id match {
    case ProfileSection.Frames => c.turn.frames.size
    case ProfileSection.CriticalMemories => c.resolved.criticalMemories.size
    case ProfileSection.Memories => c.capped(c.resolved.memories.toList, c.promptShape.memoryCap).size
    case ProfileSection.Summaries => c.capped(c.resolved.summaries.toList, c.promptShape.summaryCap).size
    case ProfileSection.Information => c.turn.information.size
    case ProfileSection.ActiveSkills => c.allSkills.size
    case ProfileSection.PreviousTopics => c.capped(c.request.previousTopics).size
    case ProfileSection.RecentTools => c.recentTools.size
    case ProfileSection.RepeatedToolCalls => c.duplicateGroups.size
    case ProfileSection.SuggestedTools => c.suggestedTools.size
    case ProfileSection.DiscoveredCapabilities => c.discovered.size
    case ProfileSection.ExtraContext => c.turn.extraContext.size
    case ProfileSection.ParticipantContext => c.perParticipantExtras.size
    case ProfileSection.ToolRoster => c.request.tools.size
    case _ => 1
  }
}
