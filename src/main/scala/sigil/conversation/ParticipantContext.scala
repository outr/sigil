package sigil.conversation

import fabric.rw.*

/**
 * Per-participant state within a conversation. Keeps participant-specific
 * data (active skills, recent tools, app-injected context) attached to the
 * participant rather than scattered across the conversation, so the
 * orchestrator can compose a participant's prompt without cross-referencing
 * multiple maps.
 *
 * @param activeSkills   loaded skill slots keyed by [[SkillSource]] so
 *                       multiple sources can contribute simultaneously
 *                       without overwriting each other.
 * @param recentTools    most-recently-used tool names, head = most recent.
 *                       Cap on size is the curator's responsibility.
 * @param suggestedTools tools recommended after the last invocation —
 *                       lets `find_capability` and discovery layers nudge
 *                       multi-step flows without forcing another lookup.
 * @param extraContext   app-keyed strings injected into this participant's
 *                       prompt. Same key replaces.
 */
case class ParticipantContext(activeSkills: Map[SkillSource, ActiveSkillSlot] = Map.empty,
                              recentTools: List[String] = Nil,
                              suggestedTools: List[String] = Nil,
                              extraContext: Map[ContextKey, String] = Map.empty) derives RW
