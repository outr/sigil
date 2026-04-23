package sigil.provider

import lightdb.id.Id
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.tool.{Tool, ToolInput}

/**
 * A conversation-aware provider request — agent turns build these. Carries
 * the topic stack, conversation view, instructions, mode, and everything
 * the framework needs to render a full system prompt and message log.
 *
 * Not serializable — `tools` carry executable behavior. If/when replay or
 * caching is needed, extract a snapshot with just the serializable fields.
 *
 * @param turnInput      the curator's per-turn output — carries the
 *                       [[sigil.conversation.ConversationView]] (frames +
 *                       participant projections) plus the memory / summary /
 *                       information selections the provider should render.
 * @param currentTopic   the active topic at request-build time. Events the
 *                       orchestrator emits (Message, ToolInvoke, …) land
 *                       under this topic unless a [[sigil.event.TopicChange]]
 *                       fires during the turn.
 * @param previousTopics earlier entries on the conversation's topic stack —
 *                       subjects this conversation has been on before and
 *                       could return to. Rendered in the system prompt as
 *                       "Previous topics" so the LLM can recognize implicit
 *                       returns.
 * @param chain          authority lineage for this invocation — the originating
 *                       participant followed by each propagator. Forwarded to
 *                       `TurnContext.chain` when a tool executes. Supplied fresh
 *                       per invocation; never persisted.
 */
case class ConversationRequest(conversationId: Id[Conversation],
                                       modelId: Id[Model],
                                       instructions: Instructions,
                                       turnInput: TurnInput,
                                       currentMode: Mode,
                                       currentTopic: TopicEntry,
                                       previousTopics: List[TopicEntry] = Nil,
                                       generationSettings: GenerationSettings,
                                       tools: Vector[Tool[? <: ToolInput]] = Vector.empty,
                                       chain: List[ParticipantId] = Nil,
                                       requestId: Id[ProviderRequest] = Id())
  extends ProviderRequest
