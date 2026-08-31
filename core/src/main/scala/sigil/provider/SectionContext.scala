package sigil.provider

import sigil.conversation.TurnInput
import sigil.participant.ParticipantId
import sigil.tool.ToolName

import scala.util.chaining.scalaUtilChainingOps

/**
 * Everything a [[ContextSection]]'s renderer needs, plus the derived
 * values several sections share.
 *
 * Sharing the derivations here is what keeps the renderer and the
 * profiler counting the same bytes: `findCapabilityAvailable` gates
 * three sections' wording, `wireToolNames` filters two, and `now`
 * anchors the relative timestamps in two.
 *
 * `featureBodies` carries the same guarantee for the effectful half:
 * [[ContextFeatures.evaluate]] runs each enabled [[ContextFeature]]
 * once and memoizes its blocks here, so every consumer that renders
 * this context reads one result rather than re-running a live lookup.
 * Empty when no features are registered, or when a context is built
 * outside the request pipeline.
 */
case class SectionContext(request: ConversationRequest,
                          resolved: ResolvedReferences,
                          discoveredCapabilitiesPromptCap: Int,
                          now: Long = System.currentTimeMillis(),
                          promptShape: PromptShape = PromptShape.Full,
                          featureBodies: Map[FeatureId, List[FeatureBody]] = Map.empty) {

  /**
   * Apply the prompt shape's general per-section entry cap to a
   * list-shaped section. `Full` caps nothing.
   */
  def capped[A](entries: List[A]): List[A] = capped(entries, promptShape.entryCap)

  /**
   * Apply a specific [[PromptShape]] cap (memories, summaries, skills)
   * to a list-shaped section.
   */
  def capped[A](entries: List[A], cap: Option[Int]): List[A] = cap.fold(entries)(entries.take)

  def turn: TurnInput = request.turnInput

  def chain: List[ParticipantId] = request.chain

  /**
   * Whether discovery is in the roster. Telling a model to call
   * `find_capability` when the active [[ToolPolicy]] excluded it is a
   * dead loop, so three sections branch their wording on this.
   */
  lazy val findCapabilityAvailable: Boolean =
    request.tools.exists(_.schema.name.value == "find_capability")

  /**
   * The roster actually going on the wire. Sections that advertise
   * tool names filter by this so the prompt's claim is accurate by
   * construction even after roster narrowing.
   */
  lazy val wireToolNames: Set[ToolName] = request.tools.map(_.schema.name).toSet

  lazy val instructionsText: String =
    if (!findCapabilityAvailable) request.instructions.renderWithoutTools
    else request.instructions.render

  /**
   * Chain-aggregated skills plus role- and mode-bundled slots.
   * `distinctBy(_.name)` keeps the mode fold idempotent with the
   * ModeChange-driven path.
   */
  lazy val allSkills: Vector[sigil.conversation.ActiveSkillSlot] =
    capped(
      (turn.aggregatedSkills(chain) ++ request.roles.flatMap(_.skill.toList) ++
        request.currentMode.skill.toList).distinctBy(_.name).toList,
      promptShape.skillCap
    ).toVector

  lazy val recentInvocations: List[sigil.conversation.RecentToolInvocation] =
    chain.flatMap(id => turn.projectionFor(id).recentToolInvocations)

  lazy val recentTools: List[sigil.conversation.RecentToolInvocation] =
    capped(recentInvocations
      .distinctBy(inv => (inv.toolName, inv.argsHash))
      .sortBy(-_.invokedAt.value)
      .take(Provider.RecentToolsPromptCap))

  lazy val duplicateGroups: List[((ToolName, String), List[sigil.conversation.RecentToolInvocation])] =
    capped(recentInvocations
      .groupBy(inv => (inv.toolName, inv.argsHash))
      .collect { case (key, occurrences) if occurrences.size > 1 => key -> occurrences }
      .toList
      .sortBy(-_._2.maxBy(_.invokedAt.value).invokedAt.value))

  lazy val suggestedTools: List[ToolName] =
    capped(chain.flatMap(id => turn.projectionFor(id).suggestedTools).distinct.filter(wireToolNames.contains))

  lazy val discovered: List[(String, List[ToolName])] =
    request.discoveredCapabilities.toList
      .sortBy(-_._2.lastSeen.value)
      .take(discoveredCapabilitiesPromptCap)
      .map { case (query, dc) => (query, dc.matches.filter(wireToolNames.contains)) }
      .filter { case (_, matches) => matches.nonEmpty }
      .pipe(capped)

  lazy val perParticipantExtras: List[(ParticipantId, (sigil.conversation.ContextKey, String))] =
    chain.flatMap(id => turn.projectionFor(id).extraContext.map(id -> _))
}
