package sigil.provider

import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.tool.{Tool, ToolInput}

/**
 * The format-neutral internal representation a [[Provider]]'s `call`
 * receives. The framework's `apply` translates each [[ProviderRequest]]
 * variant (Conversation / OneShot) into a `ProviderCall`, fully
 * resolving any DB-backed references (memories, summaries) along the way.
 *
 * Providers serialize `system` + `messages` + `tools` to their own wire
 * format (OpenAI chat-completions, Anthropic messages, etc.) inside
 * `Provider.call`. They never see [[ProviderRequest]] directly.
 *
 * @param system             pre-rendered system prompt (mode line, topic
 *                           lines, instructions, memories, summaries,
 *                           skills, tool hints, extra context — all
 *                           assembled by the shared translation pass)
 * @param messages           the conversational message log in the
 *                           framework-neutral [[ProviderMessage]] form;
 *                           empty for a OneShot whose user prompt is the
 *                           only payload (already in messages)
 * @param tools              the tools to advertise to the model
 * @param builtInTools       provider-managed tools the model can use
 *                           server-side without a client round-trip
 *                           (web search, image generation, etc.).
 *                           Providers that don't support a given
 *                           built-in drop it silently.
 * @param toolChoice         how aggressively to force a tool call
 * @param generationSettings sampling + limits passed through unchanged
 */
case class ProviderCall(
  /**
   * Sigil #277 — required Model record. Wire
   * renderers read both `model._id` (for the wire
   * `model` field) and per-model facts
   * (`contextLength`, `topProvider.maxCompletionTokens`)
   * from this record without a registry round-trip.
   */
  model: Model,
  system: String,
  messages: Vector[ProviderMessage],
  tools: Vector[Tool],
  builtInTools: Set[BuiltInTool],
  toolChoice: ToolChoice,
  generationSettings: GenerationSettings,
  currentMode: Mode = ConversationMode,
  /**
   * Conversation + agent identity threaded through so providers
   * with server-side conversation state (OpenAI Responses'
   * `previous_response_id`, Anthropic prompt caching) can key
   * their per-(agent, conversation) state. `None` for one-shot
   * requests.
   */
  conversationId: Option[Id[Conversation]] = None,
  agentId: Option[ParticipantId] = None,
  /**
   * Prior response id captured from the same (agent, conversation)
   * pair's most recent turn. When set, OpenAI's Responses provider
   * adds `previous_response_id` to the request and trims the head
   * of `messages` by `priorMessageCount` so the wire body carries
   * only the delta since that response. Other providers ignore.
   */
  previousResponseId: Option[String] = None,
  priorMessageCount: Option[Int] = None,
  /**
   * Per-attempt retry context populated by the framework's
   * transient-retry wrapper. `None` on the first attempt;
   * `Some` on every subsequent attempt, carrying the parsed
   * upstream-provider name from the prior failure so
   * providers with rotation-capable routing (OpenRouter)
   * exclude the failed upstream from this attempt.
   */
  retryContext: Option[RetryContext] = None,
  /**
   * Sigil #305 — names the framework wants to keep on the wire
   * even if the request crosses the model's context budget and
   * `Provider.emergencyShed` fires. Populated at request-build
   * time from `projection.suggestedTools`, the projection's
   * recently-used tools, and `agent.toolNames` baseline — i.e.,
   * the tools the prompt's own sections advertise to the model.
   * Without this, the shed could strip the wire roster below
   * what the prompt advertises, producing the divergence that
   * drove the change_mode loop in the field. Empty when the
   * caller doesn't construct via `runAgentTurn` (e.g.
   * `OneShotRequest` consumers).
   */
  preservedToolNames: Set[sigil.tool.ToolName] = Set.empty,
  /**
   * Sigil #302 — per-turn volatile system content
   * (Recently used tools / Repeated tool calls /
   * Suggested tools / Capabilities discovered /
   * Conversation context / Participant context /
   * Memories / Greeting hint). Providers that
   * support segmented system prompts with
   * cache-control breakpoints (Anthropic) emit
   * this as a separate segment WITHOUT
   * cache_control so its per-turn churn doesn't
   * invalidate the cached stable prefix on
   * [[system]]. Providers without that
   * facility concatenate via
   * `system + systemVolatile`. Empty when no
   * volatile content was rendered.
   */
  systemVolatile: String = "") {

  /**
   * Convenience — `model._id`. Wire serializers reach for this when
   * they just need the id for the wire `model` field; per-model facts
   * read directly off [[model]].
   */
  def modelId: lightdb.id.Id[Model] = model._id

  /**
   * Sigil #302 — single-string form (stable + volatile) for providers
   * that don't split the system prompt into segments.
   */
  def systemCombined: String =
    if (systemVolatile.isEmpty) system
    else if (system.isEmpty) systemVolatile
    else system + systemVolatile
}
