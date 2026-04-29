package sigil.participant

import fabric.rw.*
import lightdb.id.Id
import sigil.role.{GeneralistRole, Role}
import sigil.db.Model
import sigil.provider.{BuiltInTool, GenerationSettings, Instructions, ToolPolicy}
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
 * the per-turn shape lives on [[sigil.Sigil.process]], not on
 * participant subclasses.
 */
case class DefaultAgentParticipant(override val id: AgentParticipantId,
                                   override val modelId: Id[Model],
                                   override val toolNames: List[ToolName] = Nil,
                                   override val instructions: Instructions = Instructions(),
                                   override val generationSettings: GenerationSettings = GenerationSettings(),
                                   override val tools: ToolPolicy = ToolPolicy.Standard,
                                   override val builtInTools: Set[BuiltInTool] = Set.empty,
                                   override val greetsOnJoin: Boolean = false,
                                   override val roles: List[Role] = List(GeneralistRole))
  extends AgentParticipant derives RW
