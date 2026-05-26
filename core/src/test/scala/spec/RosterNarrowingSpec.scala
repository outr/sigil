package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.participant.{AgentParticipant, AgentParticipantId}
import sigil.provider.{ConversationMode, ToolPolicy}
import sigil.role.{GeneralistRole, Role}
import sigil.tool.ToolName
import sigil.tool.core.FindCapabilityTool

/**
 * Regression for sigil bug #286 — inferred tool-roster narrowing
 * based on recently-used tools. Covers:
 *
 *   1. Narrowing OFF (default): full baseline ships regardless of
 *      recent-use state.
 *   2. Narrowing ON + find_capability present + recent-use non-empty:
 *      baseline narrows to (recently-used ∩ baseline) + essentials +
 *      find_capability + suggested + extras.
 *   3. Narrowing ON + recent-use EMPTY (first iteration safety): full
 *      baseline still ships — the agent gets a wide view to start.
 *   4. Narrowing ON + find_capability ABSENT (safety): full baseline
 *      ships — no recovery path means we can't narrow safely.
 *   5. Suggested tools are preserved regardless of narrowing.
 *   6. ToolPolicy.Active extras are preserved regardless of narrowing.
 */
class RosterNarrowingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // Synthetic baseline — 10 fictional tools the agent declares.
  private val baseline: List[ToolName] = (1 to 10).map(i => ToolName(s"app_tool_$i")).toList

  /** Standard-policy agent: ToolPolicy.Standard means find_capability
    * stays in the roster (recovery path exists). */
  private case object StandardAgent extends AgentParticipant {
    override def id: AgentParticipantId      = TestAgent
    override def modelId: Id[sigil.db.Model] = sigil.db.Model.id("test", "narrow-standard")
    override def roles: List[Role]           = List(GeneralistRole)
    override def displayName: String         = "StandardAgent"
    override def avatarUrl: Option[String]   = None
    override def toolNames: List[ToolName]   = baseline
    override def tools: ToolPolicy           = ToolPolicy.Standard
  }

  /** ActiveOnly-policy agent: same baseline but find_capability is
    * stripped. Narrowing must NOT engage here — no recovery path. */
  private case object ActiveOnlyAgent extends AgentParticipant {
    override def id: AgentParticipantId      = TestAgent
    override def modelId: Id[sigil.db.Model] = sigil.db.Model.id("test", "narrow-activeonly")
    override def roles: List[Role]           = List(GeneralistRole)
    override def displayName: String         = "ActiveOnlyAgent"
    override def avatarUrl: Option[String]   = None
    override def toolNames: List[ToolName]   = baseline
    override def tools: ToolPolicy           = ToolPolicy.ActiveOnly(Nil)
  }

  private def reset(): Unit = TestSigil.narrowRosterByRecentUseOverride.set(None)

  "Sigil.effectiveToolNames (sigil #286)" should {

    "preserve the full baseline when narrowing is OFF" in Task {
      reset()
      val recent = Set(ToolName("app_tool_3"), ToolName("app_tool_7"))
      val roster = TestSigil.effectiveToolNames(
        agent = StandardAgent,
        mode = ConversationMode,
        suggested = Nil,
        overlays = Nil,
        recentlyUsedTools = recent
      )
      baseline.toSet.subsetOf(roster.toSet) shouldBe true
    }

    "narrow baseline to (recently-used ∩ baseline) when narrowing is ON and find_capability present" in Task {
      reset()
      TestSigil.narrowRosterByRecentUseOverride.set(Some(true))
      val recent = Set(ToolName("app_tool_3"), ToolName("app_tool_7"), ToolName("not_in_baseline"))
      val roster = TestSigil.effectiveToolNames(
        agent = StandardAgent,
        mode = ConversationMode,
        suggested = Nil,
        overlays = Nil,
        recentlyUsedTools = recent
      )
      // Baseline tools that ARE recently used → kept.
      roster should contain (ToolName("app_tool_3"))
      roster should contain (ToolName("app_tool_7"))
      // Baseline tools NOT recently used → dropped.
      roster should not contain ToolName("app_tool_1")
      roster should not contain ToolName("app_tool_4")
      // Recently-used items not in baseline don't materialise from the narrowing path
      // (they'd only be in suggested or essentials).
      roster should not contain ToolName("not_in_baseline")
      // find_capability is always present.
      roster should contain (FindCapabilityTool.schema.name)
    }

    "preserve full baseline when recentlyUsedTools is EMPTY (first-iteration safety)" in Task {
      reset()
      TestSigil.narrowRosterByRecentUseOverride.set(Some(true))
      val roster = TestSigil.effectiveToolNames(
        agent = StandardAgent,
        mode = ConversationMode,
        suggested = Nil,
        overlays = Nil,
        recentlyUsedTools = Set.empty
      )
      baseline.toSet.subsetOf(roster.toSet) shouldBe true
    }

    "preserve full baseline when find_capability is absent (no recovery path)" in Task {
      reset()
      TestSigil.narrowRosterByRecentUseOverride.set(Some(true))
      val recent = Set(ToolName("app_tool_3"))
      val roster = TestSigil.effectiveToolNames(
        agent = ActiveOnlyAgent,
        mode = ConversationMode,
        suggested = Nil,
        overlays = Nil,
        recentlyUsedTools = recent
      )
      // Narrowing is gated on find_capability presence; ActiveOnly strips it,
      // so all 10 baseline tools must still ship.
      baseline.toSet.subsetOf(roster.toSet) shouldBe true
      roster should not contain FindCapabilityTool.schema.name
    }

    "suggested tools survive narrowing" in Task {
      reset()
      TestSigil.narrowRosterByRecentUseOverride.set(Some(true))
      val recent = Set(ToolName("app_tool_3"))
      val suggested = List(ToolName("brand_new_discovery"))
      val roster = TestSigil.effectiveToolNames(
        agent = StandardAgent,
        mode = ConversationMode,
        suggested = suggested,
        overlays = Nil,
        recentlyUsedTools = recent
      )
      roster should contain (ToolName("brand_new_discovery"))
      roster should contain (ToolName("app_tool_3"))
    }

    "ToolPolicy.Active extras survive narrowing" in Task {
      reset()
      TestSigil.narrowRosterByRecentUseOverride.set(Some(true))
      val recent = Set(ToolName("app_tool_3"))
      val overlay = ToolPolicy.Active(List(ToolName("metals_find_symbol")))
      val roster = TestSigil.effectiveToolNames(
        agent = StandardAgent,
        mode = ConversationMode,
        suggested = Nil,
        overlays = List(overlay),
        recentlyUsedTools = recent
      )
      roster should contain (ToolName("metals_find_symbol"))
      roster should contain (ToolName("app_tool_3"))
      roster should not contain ToolName("app_tool_1")
    }
  }

  "tear down" should {
    "dispose TestSigil" in {
      reset()
      TestSigil.shutdown.map(_ => succeed)
    }
  }
}
