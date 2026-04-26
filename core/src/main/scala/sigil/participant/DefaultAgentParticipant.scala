package sigil.participant

import fabric.rw.*
import lightdb.id.Id
import sigil.behavior.{Behavior, GeneralistBehavior}
import sigil.db.Model
import sigil.provider.{GenerationSettings, Instructions}
import sigil.tool.ToolName

/**
 * Framework-supplied concrete [[AgentParticipant]]. Persists directly on
 * [[sigil.conversation.Conversation.participants]] via the `Participant`
 * poly RW — no subclassing required for a vanilla LLM agent.
 *
 * Apps that need extra fields (label, persona, etc.) subclass
 * [[AgentParticipant]] with their own case class, `derives RW`, and
 * register its RW via `Sigil.participants`. The poly discriminator
 * handles heterogeneous deserialization. App-level customization of
 * the per-behavior turn shape lives on [[sigil.Sigil.process]], not
 * on participant subclasses.
 */
case class DefaultAgentParticipant(override val id: AgentParticipantId,
                                   override val modelId: Id[Model],
                                   override val toolNames: List[ToolName] = Nil,
                                   override val instructions: Instructions = Instructions(),
                                   override val generationSettings: GenerationSettings = GenerationSettings(),
                                   override val behaviors: List[Behavior] = List(GeneralistBehavior))
  extends AgentParticipant derives RW
