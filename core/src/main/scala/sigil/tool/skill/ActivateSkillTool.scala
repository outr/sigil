package sigil.tool.skill

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.conversation.{ActiveSkillSlot, SkillSource}
import sigil.skill.Skill
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, Resolution, TextToolOutput, Tool, ToolIO, ToolName, ToolProfile, ToolResult, ToolSpec}

/**
 * Loads a [[sigil.skill.Skill]] into the agent's
 * [[sigil.conversation.ParticipantProjection.activeSkills]] under
 * [[SkillSource.Discovery]]. Called after `find_capability` returns a
 * `Skill` match — the match's hint is `activate_skill("name")`.
 *
 * Lifecycle: a Discovery skill stays active until either the agent
 * activates a different skill (replacement) or the conversation
 * changes mode. On `change_mode`, the framework archives the
 * Discovery slot under the OUTGOING mode's id and restores any
 * previously-archived slot for the INCOMING mode (see
 * `Sigil.applyModeSkill`). So a coding-mode skill survives a
 * detour into conversation mode and reappears when the agent
 * returns to coding.
 *
 * Mode-scope check: skills declare which modes they're available in
 * via `Skill.modes`. If the requested skill exists but doesn't
 * include the current mode (or the skill isn't found at all), the
 * tool emits a not-supported message rather than activating
 * silently.
 */
case object ActivateSkillTool extends Tool {
  type Input  = ActivateSkillInput
  type Output = TextToolOutput
  val io: ToolIO[ActivateSkillInput, TextToolOutput] = ToolIO.derived[ActivateSkillInput, TextToolOutput]

  override val name: ToolName = ToolName("activate_skill")
  override val description: String =
    """Activate a discovered Skill — a system-prompt overlay that specializes you for a focused
      |task. Pass the skill's `name` (returned by capability discovery).
      |
      |The skill stays active until you activate a different one or the conversation changes
      |mode. On a mode change the framework archives your active skill under the outgoing
      |mode and restores any archived skill for the incoming mode — so per-mode skill state
      |survives detours.
      |
      |If the skill isn't found OR isn't available in the current mode, this tool reports
      |the failure and changes nothing.""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("activate", "skill", "load", "enable", "use"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: ActivateSkillInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    context.sigil.withDB(_.skills.transaction(_.get(lightdb.id.Id[Skill](input.name)))).flatMap {
      case None =>
        Task.pure(ToolResult.failure(
          message = s"[activate_skill] no Skill found with name '${input.name}'.",
          hint = Some("Call find_capability to discover an available Skill, then activate it by its exact name.")
        ))
      case Some(skill) =>
        val currentMode = context.conversation.currentMode
        val modeOk = skill.modes.isEmpty || skill.modes.contains(currentMode.id)
        if (!modeOk)
          Task.pure(ToolResult.failure(
            message = s"[activate_skill] Skill '${skill.name}' is not available in mode '${currentMode.name}'.",
            hint = Some("Switch to a mode this skill supports via change_mode, or pick a skill available in the current mode.")
          ))
        else {
          val slot = ActiveSkillSlot(name = skill.name, content = skill.content)
          context.sigil.updateProjection(context.conversation.id, context.caller) { proj =>
            proj.copy(
              activeSkills = proj.activeSkills + (SkillSource.Discovery -> slot),
              discoverySkillMode = Some(currentMode.id)
            )
          }.map(_ => ToolResult.Success(TextToolOutput(s"[activate_skill] Skill '${skill.name}' is now active.")))
        }
    }
}
