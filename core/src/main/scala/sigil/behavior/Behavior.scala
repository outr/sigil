package sigil.behavior

import fabric.rw.*
import sigil.conversation.ActiveSkillSlot
import sigil.provider.ToolPolicy

/**
 * Permanent role assignment carried by an [[sigil.participant.AgentParticipant]].
 * Behaviors compose alongside [[sigil.provider.Mode]]: Mode is the
 * conversation-level operating context (mutable via `change_mode`),
 * Behavior is the agent-level identity (immutable for the agent's
 * lifetime — agents do not change their own behaviors).
 *
 * A Behavior contributes three things to an agent's turn:
 *   - `description` — prompt fragment rendered as the content of an
 *     [[ActiveSkillSlot]] keyed under [[sigil.conversation.SkillSource.Behavior]],
 *     flowing through the existing `aggregatedSkills` → `renderSystem`
 *     pipeline. Empty descriptions contribute no slot.
 *   - `skill` — explicit override for the slot's content. When set,
 *     replaces the description-derived slot.
 *   - `tools` — [[ToolPolicy]] folded with the current Mode's policy by
 *     [[sigil.Sigil.effectiveToolNames]] to compose the agent's effective
 *     roster for the turn.
 *
 * Pure data — case class, RW round-trips trivially. App customization
 * lives on the framework via [[sigil.Sigil.process]] which receives the
 * active behavior on each per-behavior dispatch and can specialize by
 * `name`.
 *
 * `AgentParticipant.behaviors` defaults to `List(GeneralistBehavior)`
 * and is enforced non-empty; an agent always has a real role.
 */
case class Behavior(name: String,
                    description: String,
                    skill: Option[ActiveSkillSlot] = None,
                    tools: ToolPolicy = ToolPolicy.Standard) derives RW
