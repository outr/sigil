package sigil.provider

import lightdb.id.Id
import sigil.conversation.{Conversation, ConversationContext}
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.tool.{Tool, ToolInput}

/**
 * Runtime request to a provider. Not serializable — `tools` carry executable
 * behavior. If/when replay or caching is needed, extract a snapshot with just
 * the serializable fields.
 *
 * @param context the structured conversation context the orchestrator builds
 *                this turn's prompt from. Replaces a flat `Vector[Event]` —
 *                providers read `context.events` (and may consult memories,
 *                summaries, information catalog, extra context) to compose
 *                their wire payload.
 * @param chain   authority lineage for this invocation — the originating
 *                participant followed by each propagator. Forwarded to
 *                `TurnContext.chain` when a tool executes. Supplied fresh per
 *                invocation; never persisted.
 */
case class ProviderRequest(conversationId: Id[Conversation],
                           modelId: Id[Model],
                           instructions: Instructions,
                           context: ConversationContext,
                           currentMode: Mode,
                           generationSettings: GenerationSettings,
                           tools: Vector[Tool[? <: ToolInput]] = Vector.empty,
                           chain: List[ParticipantId] = Nil,
                           requestId: Id[ProviderRequest] = Id())
