package sigil.participant

import fabric.rw.RW

/**
 * Typed [[ParticipantId]] for participants that represent an agent (LLM-backed,
 * automated, behavior-driven). Apps implement this for each concrete agent type
 * in their system.
 *
 * Used as the `agentId` on [[sigil.event.AgentState]] so the agent's activity
 * lifecycle is type-correlated with its participant identity.
 */
trait AgentParticipantId extends ParticipantId

object AgentParticipantId {
  import ParticipantId.given

  /**
   * Delegates to the `ParticipantId` poly for serialization. Every
   * `AgentParticipantId` is a `ParticipantId`, so the poly RW dispatches
   * correctly at runtime — apps register their concrete agent id types via
   * `Sigil.participantIds`.
   */
  given RW[AgentParticipantId] =
    summon[RW[ParticipantId]].asInstanceOf[RW[AgentParticipantId]]
}
