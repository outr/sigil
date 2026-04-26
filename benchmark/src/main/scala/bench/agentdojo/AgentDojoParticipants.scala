package bench.agentdojo

import sigil.participant.{AgentParticipantId, ParticipantId}

/** Viewer (user-side) participant id used across AgentDojo benchmark
  * runs. Matches AgentDojo's "Emma Johnson" user persona — see
  * [[bench.agentdojo.banking.BankingFixture]]. */
case object AgentDojoUser extends ParticipantId {
  override val value: String = "agentdojo-user"
}

/** Agent participant id. Banking, slack, travel, and workspace suites
  * share this id since each scenario is one user → one agent. */
case object AgentDojoAgent extends AgentParticipantId {
  override val value: String = "agentdojo-agent"
}
