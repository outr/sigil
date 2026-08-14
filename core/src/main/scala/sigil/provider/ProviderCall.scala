package sigil.provider

import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.tool.{Tool, ToolRoster}

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
 * @param system             pre-rendered stable half of the system prompt
 *                           (mode line, instructions, roles, skills,
 *                           pinned directives) — the span a prompt cache
 *                           keys on. The per-turn half lives on
 *                           [[ProviderCall.systemVolatile]].
 * @param messages           the conversational message log in the
 *                           framework-neutral [[ProviderMessage]] form;
 *                           empty for a OneShot whose user prompt is the
 *                           only payload (already in messages)
 * @param roster             the tools to advertise to the model, as the
 *                           request's single name→tool resolution source —
 *                           the streaming accumulator, token estimator, and
 *                           request-cache key all read this one instance
 * @param builtInTools       provider-managed tools the model can use
 *                           server-side without a client round-trip
 *                           (web search, image generation, etc.).
 *                           Providers that don't support a given
 *                           built-in drop it silently.
 * @param toolChoice         how aggressively to force a tool call
 * @param generationSettings sampling + limits passed through unchanged
 */
case class ProviderCall(/** Sigil #277 — required Model record. Wire
                          * renderers read both `model._id` (for the wire
                          * `model` field) and per-model facts
                          * (`contextLength`, `topProvider.maxCompletionTokens`)
                          * from this record without a registry round-trip. */
                        model: Model,
                        system: String,
                        messages: Vector[ProviderMessage],
                        roster: ToolRoster,
                        builtInTools: Set[BuiltInTool],
                        toolChoice: ToolChoice,
                        generationSettings: GenerationSettings,
                        currentMode: Mode = ConversationMode,
                        /** Conversation + agent identity threaded through so providers
                          * with server-side conversation state (OpenAI Responses'
                          * `previous_response_id`, Anthropic prompt caching) can key
                          * their per-(agent, conversation) state. `None` for one-shot
                          * requests. */
                        conversationId: Option[Id[Conversation]] = None,
                        agentId: Option[ParticipantId] = None,
                        /** Prior response id captured from the same (agent, conversation)
                          * pair's most recent turn. When set, OpenAI's Responses provider
                          * adds `previous_response_id` to the request and trims the head
                          * of `messages` by `priorMessageCount` so the wire body carries
                          * only the delta since that response. Other providers ignore. */
                        previousResponseId: Option[String] = None,
                        priorMessageCount: Option[Int] = None,
                        /** Per-attempt retry context populated by the framework's
                          * transient-retry wrapper. `None` on the first attempt;
                          * `Some` on every subsequent attempt, carrying the parsed
                          * upstream-provider name from the prior failure so
                          * providers with rotation-capable routing (OpenRouter)
                          * exclude the failed upstream from this attempt. */
                        retryContext: Option[RetryContext] = None,
                        /** Sigil #305 — names the framework wants to keep on the wire
                          * even if the request crosses the model's context budget and
                          * `Provider.emergencyShed` fires. Populated at request-build
                          * time from `projection.suggestedTools`, the projection's
                          * recently-used tools, and `agent.toolNames` baseline — i.e.,
                          * the tools the prompt's own sections advertise to the model.
                          * Without this, the shed could strip the wire roster below
                          * what the prompt advertises, producing the divergence that
                          * drove the change_mode loop in the field. Empty when the
                          * caller doesn't construct via `runAgentTurn` (e.g.
                          * `OneShotRequest` consumers). */
                        preservedToolNames: Set[sigil.tool.ToolName] = Set.empty,
                        /** Per-turn volatile context (Current topic /
                          * Previous topics / Referenced content /
                          * Summaries / Memories / Recently used tools /
                          * Repeated tool calls / Suggested tools /
                          * Capabilities discovered / Conversation
                          * context / Participant context / Greeting
                          * hint). Prompt caches key
                          * on a byte-stable request PREFIX, so this
                          * churning segment must ride BEHIND every
                          * cacheable span — full-history-replay
                          * providers render [[system]] alone as the
                          * system prompt and append this via
                          * [[messagesWithVolatileTail]]; anywhere
                          * earlier (even uncached, as a trailing system
                          * segment) it sits upstream of the message
                          * history and invalidates the history's cache
                          * prefix on every turn. Providers with
                          * server-side conversation state (OpenAI
                          * Responses) keep [[systemCombined]] instead —
                          * their per-request `instructions` channel is
                          * not part of an accumulated transcript. Empty
                          * when no volatile content was rendered. */
                        systemVolatile: String = "") {
  /** Convenience — `model._id`. Wire serializers reach for this when
    * they just need the id for the wire `model` field; per-model facts
    * read directly off [[model]]. */
  def modelId: lightdb.id.Id[Model] = model._id

  /** Convenience — the roster's tool list, in advertisement order. */
  def tools: Vector[Tool] = roster.tools

  /** Single-string form (stable + volatile) for providers whose
    * system channel is not part of a cacheable prefix (OpenAI
    * Responses' per-request `instructions`). Full-history-replay
    * providers use [[system]] + [[messagesWithVolatileTail]] instead. */
  def systemCombined: String =
    if (systemVolatile.isEmpty) system
    else if (system.isEmpty) systemVolatile
    else system + systemVolatile

  /** [[messages]] with the volatile per-turn segment appended as a
    * trailing System entry. Full-history-replay providers render this
    * (with [[system]] alone as the system prompt) so the per-turn
    * churn rides AFTER every cache breakpoint: the prefix a prompt
    * cache keys on — tools, system prompt, message history — stays
    * byte-stable across turns, and only the never-cached tail changes.
    * Each provider's existing mid-conversation System rendering gives
    * the tail a sensible wire shape (a `[system]`-marked user message
    * on Anthropic / Google, a system-role message on OpenAI-compatible
    * dialects). */
  def messagesWithVolatileTail: Vector[ProviderMessage] =
    if (systemVolatile.isEmpty) messages
    else messages :+ ProviderMessage.System(systemVolatile)
}
