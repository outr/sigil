package sigil.provider.cloudflare

import rapid.{Stream, Task}
import sigil.Sigil
import sigil.provider.*
import sigil.provider.wire.OpenAIChatCompletions
import spice.http.HttpRequest
import spice.net.*

import scala.concurrent.duration.*

/**
 * Cloudflare Workers AI provider — OpenAI-compatible chat-completions
 * at `https://api.cloudflare.com/client/v4/accounts/{accountId}/ai/v1/chat/completions`.
 * Hosts Moonshot's Kimi family (`@cf/moonshotai/kimi-k2.5`,
 * `@cf/moonshotai/kimi-k2.6`) and other Workers AI catalog models on
 * Cloudflare's own infrastructure (not a third-party reseller). Model
 * id passed on the [[ProviderCall]] selects which.
 *
 * Cloudflare's Kimi deployments (K2.5, K2.6) ship with substantially
 * larger context windows than DO's — K2.6 documents 262K. Reasoning
 * chains have more headroom before hitting `max_tokens`, mitigating
 * chains have more headroom before hitting `max_tokens`, mitigating
 * (but not eliminating) the `empty_budget_burn` degeneration mode. The defensive
 */
case class CloudflareProvider(apiToken: String,
                              accountId: String,
                              sigilRef: Sigil,
                              baseUrl: URL = url"https://api.cloudflare.com",
                              /**
                               * Per-read idle timeout for the SSE stream. Fires
                               * only when no bytes arrive for the duration —
                               * slow-but-working streams keep going.
                               */
                              tokenIdleTimeout: FiniteDuration = 120.seconds)
  extends Provider {
  override def `type`: ProviderType = ProviderType.Cloudflare
  override val providerKey: String = Cloudflare.Provider
  override protected def sigil: Sigil = sigilRef

  private val wireConfig: OpenAIChatCompletions.Config = OpenAIChatCompletions.Config(
    providerNamespace = Cloudflare.Provider,
    providerName = "Cloudflare",
    path = s"/client/v4/accounts/$accountId/ai/v1/chat/completions",
    // Cloudflare's OpenAI-compat layer documents structured outputs
    // (`response_format`, strict JSON schema). Strict shaping engages
    // by default; live tests will tell us if it's honored end-to-end.
    strictModeCapable = true,
    // Native canonical `reasoning_effort` — the framework translates
    // ReasoningMode (Auto/On/Off) + optional Effort into the wire field.
    // Native canonical `reasoning_effort` — the framework translates
    // ReasoningMode (Auto/On/Off) + optional Effort into the wire field.
    reasoningPolicy = OpenAIChatCompletions.ReasoningPolicy.ReasoningEffortField,
    // Kimi-K2.6 can pause minutes between reasoning and the answer; give
    // streaming requests a longer idle budget than the 120s base.
    reasoningIdleTimeout = Some(6.minutes),
    // Kimi-K2.6 rejects `reasoning_effort:"none"` (AiError → empty
    // completion); disable thinking via the vLLM `enable_thinking` toggle.
    reasoningOffUsesThinkingToggle = true,
    // Kimi-K2.6 reasons unboundedly under Auto and never tool-calls; treat
    // Auto as off so it terminates. Explicit On/Off is still honored.
    treatAutoAsReasoningOff = true,
    multimodalPolicy = OpenAIChatCompletions.MultimodalPolicy.OpenAIArrayForm,
    // Defensive: Kimi can still degenerate even under more capable
    // hosting. Throwing on the empty-budget burn pattern lets a
    // ProviderStrategy route to the next candidate when it fires.
    emptyBudgetBurnThrows = true,
    // Cloudflare's reasoning models (Kimi, gpt-oss) drop tool calls on the
    // STREAMING endpoint when the model reasons before deciding to call a
    // tool — `finish_reason: stop` with no tool_calls — but return the call
    // correctly on a single NON-streaming request (verified by raw curl).
    // Default to non-streaming so agentic tool use is reliable; a per-call
    // `GenerationSettings.streaming = Some(true)` can opt back in.
    streaming = false
  )

  private val bearerAuth: HttpRequest => HttpRequest =
    _.withHeader("Authorization", s"Bearer $apiToken")

  override def call(input: ProviderCall): Stream[ProviderEvent] =
    OpenAIChatCompletions.streamCall(input, sigilRef, baseUrl, bearerAuth, tokenIdleTimeout, wireConfig)

  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
    OpenAIChatCompletions.buildHttpRequest(input, sigilRef, baseUrl, bearerAuth, wireConfig)
}

object CloudflareProvider {

  /**
   * Construct a [[CloudflareProvider]] and seed its Workers AI model
   * catalog into [[sigil.cache.ModelRegistry]] from Cloudflare's
   * `/client/v4/accounts/<accountId>/ai/models/search` endpoint.
   * Mirrors [[sigil.provider.llamacpp.LlamaCppProvider.apply]]'s
   * contract — the registry is the source of truth for the
   * pre-flight budget gate, routed model selection, and the
   * post-#311 boundary check on tool inputs that accept a
   * `modelId`. Any Workers AI model the provider can serve must
   * land in the cache before a turn runs against it; this factory
   * is the documented path.
   */
  def apply(sigil: Sigil,
            apiToken: String,
            accountId: String): Task[CloudflareProvider] =
    apply(sigil, apiToken, accountId, url"https://api.cloudflare.com", 120.seconds)

  def apply(sigil: Sigil,
            apiToken: String,
            accountId: String,
            baseUrl: URL,
            tokenIdleTimeout: FiniteDuration): Task[CloudflareProvider] =
    Cloudflare.refreshModels(sigil, accountId, apiToken, baseUrl).map { _ =>
      CloudflareProvider(apiToken, accountId, sigil, baseUrl, tokenIdleTimeout)
    }

  /**
   * Backwards-compat no-auto-load construction. Use when an app
   * wires its own [[Model]] records via `cache.merge` (e.g. a
   * pinned single-model deployment that doesn't need the full
   * catalog walk, an offline test fixture, an app that already
   * fetched the catalog elsewhere). New code should prefer
   * [[apply]] which auto-loads.
   */
  def create(sigil: Sigil,
             apiToken: String,
             accountId: String,
             baseUrl: URL = url"https://api.cloudflare.com"): Task[CloudflareProvider] =
    Task.pure(CloudflareProvider(apiToken, accountId, sigil, baseUrl))
}
