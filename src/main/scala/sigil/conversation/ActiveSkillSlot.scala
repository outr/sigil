package sigil.conversation

import fabric.rw.*

/**
 * A loaded skill slot active for a participant. Stores the skill content
 * inline rather than a reference, so the prompt builder doesn't need to
 * resolve a registry lookup at every turn.
 *
 * Multiple slots can be active simultaneously (e.g. one from the current
 * mode, one promoted by `find_capability`, one set by an explicit user
 * directive); they're keyed by source on [[ParticipantProjection.activeSkills]].
 */
case class ActiveSkillSlot(name: String, content: String) derives RW
