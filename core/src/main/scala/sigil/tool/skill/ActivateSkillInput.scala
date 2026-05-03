package sigil.tool.skill

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the `activate_skill` tool. The agent calls this with the
 * `name` of a [[sigil.skill.Skill]] surfaced in a prior
 * [[sigil.tool.discovery.CapabilityMatch]] of type `Skill`. The tool
 * loads the skill into the participant's
 * [[sigil.conversation.ParticipantProjection.activeSkills]] under
 * [[sigil.conversation.SkillSource.Discovery]], where it overlays the
 * system prompt on subsequent turns.
 */
case class ActivateSkillInput(name: String) extends ToolInput derives RW
