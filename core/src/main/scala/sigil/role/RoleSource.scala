package sigil.role

import rapid.Task
import sigil.TurnContext
import sigil.participant.AgentParticipantId

/**
 * Pluggable resolver for an agent's [[Role]] list at turn time.
 *
 * Static agents declare their roles inline as a `List[Role]` and never
 * need a `RoleSource` — the framework defaults to returning the agent's
 * declared `roles` field synchronously.
 *
 * Apps that store agent identity in a database (mutable user-owned
 * personas, persona templates, role marketplaces, role-by-tenant
 * overrides) supply a `RoleSource` so each turn pulls the current
 * roles from persistence. Voidcraft's `Persona` document, Sage's
 * `PersonaCollection`, and similar designs all implement this trait.
 *
 * The resolver runs once per turn before the prompt-rendering pass,
 * inside [[sigil.Sigil.runAgentTurn]]. Failures bubble up as a normal
 * `Task` error — the framework treats them like provider failures and
 * surfaces a `ProviderEvent.Error` to the conversation.
 *
 * Implementations should be idempotent and side-effect-free at the
 * caller's level; cache results in your own layer if the lookup is
 * expensive (DB-backed personas typically already use lightdb's
 * transaction cache).
 */
trait RoleSource {

  /**
   * Resolve the role list for the given agent at this moment. Empty
   * results are rejected at the call site; if you need to express
   * "no special role," return `List(GeneralistRole)` explicitly.
   */
  def rolesFor(agentId: AgentParticipantId, context: TurnContext): Task[List[Role]]
}

object RoleSource {

  /**
   * Wrap a static `List[Role]` as a [[RoleSource]] that always returns
   * those roles. Equivalent to declaring the roles inline on the
   * `AgentParticipant`; provided for symmetry when you have a mix of
   * static and dynamic agents.
   */
  def static(roles: List[Role]): RoleSource = new RoleSource {
    override def rolesFor(agentId: AgentParticipantId, context: TurnContext): Task[List[Role]] =
      Task.pure(roles)
  }

  /**
   * The framework default — returns whatever the agent declares
   * synchronously on its `roles` field. Used by
   * [[sigil.participant.AgentParticipant.resolveRoles]] when no
   * dynamic source is wired.
   */
  val Static: RoleSource = new RoleSource {
    override def rolesFor(agentId: AgentParticipantId, context: TurnContext): Task[List[Role]] =
      Task.pure(Nil) // unused — the default impl reads agent.roles directly
  }
}
