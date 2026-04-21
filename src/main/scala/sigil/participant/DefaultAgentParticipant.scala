package sigil.participant

import fabric.rw.*
import lightdb.id.Id
import sigil.db.Model
import sigil.provider.{GenerationSettings, Instructions}

/**
 * Framework-supplied concrete [[AgentParticipant]]. Persists directly on
 * [[sigil.conversation.Conversation.participants]] via the `Participant`
 * poly RW — no subclassing required for a vanilla LLM agent.
 *
 * Apps that need custom loop behavior (Planner, Critic, etc.) subclass
 * [[AgentParticipant]] with their own case class, `derives RW`, and
 * register its RW via `Sigil.participants`. The poly discriminator
 * handles heterogeneous deserialization.
 */
case class DefaultAgentParticipant(override val id: AgentParticipantId,
                                   override val modelId: Id[Model],
                                   override val toolNames: List[String] = Nil,
                                   override val instructions: Instructions = Instructions(),
                                   override val generationSettings: GenerationSettings = GenerationSettings())
  extends AgentParticipant derives RW
