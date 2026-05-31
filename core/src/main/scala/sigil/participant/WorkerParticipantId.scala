package sigil.participant

import fabric.rw.*

/**
 * Framework-supplied [[AgentParticipantId]] for a delegated worker agent
 * minted at runtime by `delegate_task`. Workers are created dynamically —
 * one per delegation — so, unlike app-defined agent ids (case objects
 * registered up front), they need a value-carrying identity. This case
 * class gives every worker a serializable, unique id. Its RW is
 * registered once by `Sigil.instance` so worker participants round-trip
 * on persisted conversations without app wiring.
 */
case class WorkerParticipantId(override val value: String) extends AgentParticipantId derives RW
