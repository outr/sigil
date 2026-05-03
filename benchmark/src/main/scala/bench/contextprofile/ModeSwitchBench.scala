package bench.contextprofile

import sigil.conversation.{ActiveSkillSlot, ParticipantProjection, SkillSource}
import sigil.provider.{ConversationMode, Mode, ToolPolicy}
import sigil.role.Role
import sigil.tool.core.{ChangeModeTool, FindCapabilityTool, RespondTool, StopTool}

/**
 * Phase 0 — mode-switch profile. Agent alternates between a Coding mode
 * and Conversation mode every 5 turns, with a discovery-activated
 * skill in each mode. Tracks how active-skills + role contributions
 * grow / churn through the conversation.
 *
 * Run: `sbt "benchmark/runMain bench.contextprofile.ModeSwitchBench"`
 * Output: `benchmark/profiles/mode-switch.md`
 */
object ModeSwitchBench {

  case object CodingMode extends Mode {
    override val name: String = "coding"
    override val description: String = "Coding mode — code generation, refactoring, review, debug."
    override val skill: Option[ActiveSkillSlot] = Some(ActiveSkillSlot(
      name = "coding-baseline",
      content = "Apply standard Sigil conventions: scala 3 enums over strings, typed wrappers, no half-wired features. " +
        "Prefer editing existing files; avoid premature abstractions; comments only when WHY is non-obvious."
    ))
    override val tools: ToolPolicy = ToolPolicy.Standard
  }

  private val codingDeepSkill = ActiveSkillSlot(
    name = "coding-deep",
    content = "When refactoring: identify all call sites first, plan in dependency order, run tests after each step. " +
      "Use git mv for renames. Don't widen APIs to satisfy tests."
  )

  private val researchSkill = ActiveSkillSlot(
    name = "research",
    content = "When researching: cite sources, prefer primary documentation over blogs, " +
      "summarize differences between approaches before recommending one."
  )

  private val plannerRole = Role(
    name = "planner",
    description = "Decompose tasks into discrete steps. Plan before acting. Surface architectural tradeoffs explicitly."
  )

  def main(args: Array[String]): Unit = {
    val turns = 30
    val tools = Vector[sigil.tool.Tool](RespondTool, FindCapabilityTool, StopTool, ChangeModeTool)

    val profiles = (1 to turns).map { n =>
      val mode: Mode = if ((n / 5) % 2 == 0) ConversationMode else CodingMode

      // Discovery skill: alternates with mode + occasionally swapped
      val discoverySkill = if (mode == CodingMode) codingDeepSkill else researchSkill

      val activeSkills = Map[SkillSource, ActiveSkillSlot](
        SkillSource.Mode      -> mode.skill.getOrElse(ActiveSkillSlot("none", "")),
        SkillSource.Discovery -> discoverySkill,
        SkillSource.User      -> ActiveSkillSlot("user-pref", "Always confirm before destructive actions.")
      )

      val projections: Map[sigil.participant.ParticipantId, ParticipantProjection] = Map(
        ProfilerHarness.AgentId -> ParticipantProjection(activeSkills = activeSkills)
      )

      val frames = (1 to n).flatMap { t =>
        Vector(
          ProfilerHarness.textFrame(s"User turn $t."),
          ProfilerHarness.textFrame(s"Agent reply $t.", ProfilerHarness.AgentId)
        )
      }.toVector

      val view = ProfilerHarness.viewWith(frames, projections)
      val req = ProfilerHarness.buildRequest(view, tools, mode = mode, roles = List(plannerRole))
      ProfilerHarness.profile(req)
    }

    ProfilerHarness.writeReport(
      "mode-switch",
      "Phase 0 — Mode Switching (alternating modes + skill churn)",
      profiles
    )
  }
}
