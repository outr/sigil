package sigil.tool.skill

import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.conversation.{ActiveSkillSlot, SkillSource}
import sigil.event.{Event, Message, MessageRole}
import sigil.skill.Skill
import sigil.tool.{ToolName, TypedTool}
import sigil.tool.model.ResponseContent

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
case object ActivateSkillTool extends TypedTool[ActivateSkillInput](
  name = ToolName("activate_skill"),
  description =
    """Activate a discovered Skill — a system-prompt overlay that specializes you for a focused
      |task. Call this AFTER `find_capability` returned a Skill match; pass the match's `name`.
      |
      |The skill stays active until you activate a different one or the conversation changes
      |mode. On a mode change the framework archives your active skill under the outgoing
      |mode and restores any archived skill for the incoming mode — so per-mode skill state
      |survives detours.
      |
      |If the skill isn't found OR isn't available in the current mode, this tool reports
      |the failure and changes nothing.""".stripMargin,
  keywords = Set("activate", "skill", "load", "enable", "use")
) {
  override protected def executeTyped(input: ActivateSkillInput, context: TurnContext): Stream[Event] =
    Stream.force(activate(input, context).map { messageText =>
      Stream.emits(List[Event](Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text(messageText)),
        role = MessageRole.Tool
      )))
    })

  private def activate(input: ActivateSkillInput, context: TurnContext): Task[String] =
    context.sigil.withDB(_.skills.transaction(_.get(lightdb.id.Id[Skill](input.name)))).flatMap {
      case None =>
        Task.pure(s"[activate_skill] no Skill found with name '${input.name}'.")
      case Some(skill) =>
        val currentMode = context.conversation.currentMode
        val modeOk = skill.modes.isEmpty || skill.modes.contains(currentMode.id)
        if (!modeOk) Task.pure(
          s"[activate_skill] Skill '${skill.name}' is not available in mode '${currentMode.name}'."
        )
        else {
          val slot = ActiveSkillSlot(name = skill.name, content = skill.content)
          context.sigil.updateProjection(context.conversation.id, context.caller) { proj =>
            proj.copy(
              activeSkills = proj.activeSkills + (SkillSource.Discovery -> slot),
              discoverySkillMode = Some(currentMode.id)
            )
          }.map(_ => s"[activate_skill] Skill '${skill.name}' is now active.")
        }
    }
}
