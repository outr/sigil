package sigil.role

import fabric.rw.*
import sigil.conversation.ActiveSkillSlot

/**
 * Atomic role assignment carried by an [[sigil.participant.AgentParticipant]].
 * Roles compose alongside [[sigil.provider.Mode]]: Mode is the
 * conversation-level operating context (mutable via `change_mode`),
 * Role is the agent-level identity — assigned at construction,
 * immutable for the agent's lifetime.
 *
 * A `Role` contributes two things to the agent's system prompt:
 *   - `description` — the role's scope/identity statement ("You plan
 *     tasks, decompose user goals, and reason about ordering"). Renders
 *     into the system prompt's role-scope section.
 *   - `skill` — optional explicit [[ActiveSkillSlot]] containing the
 *     atomic capability text the model should keep in mind. When set,
 *     it's appended to the participant projection's "Active skills"
 *     section under [[sigil.conversation.SkillSource.Role]].
 *
 * Roles are pure data — case class, fabric RW round-trips trivially.
 *
 * **Composition.** Agents that fulfill multiple roles carry them as a
 * list on `AgentParticipant.roles: List[Role]`. The framework's
 * prompt rendering branches on list shape: a single role renders
 * linearly; multiple roles render with a "You serve the following
 * roles:" preamble + per-role enumeration so the model handles
 * multi-role identity explicitly. Apps write each Role's description
 * self-contained (as if that role is the whole agent) and let the
 * framework reconcile the multi-role case in rendering.
 *
 * **Tools, greeting, and dispatch overrides** are intentionally NOT
 * carried on Role — they're agent-level concerns. `AgentParticipant`
 * carries `tools: ToolPolicy` (one effective roster per agent),
 * `greetsOnJoin: Boolean` (one lifecycle decision per agent), and
 * non-LLM dispatch overrides live on a separate participant trait
 * entirely.
 */
case class Role(name: String,
                description: String,
                skill: Option[ActiveSkillSlot] = None) derives RW
