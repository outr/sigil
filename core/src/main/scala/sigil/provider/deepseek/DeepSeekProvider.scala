package sigil.provider.deepseek

import rapid.{Stream, Task}
import sigil.Sigil
import sigil.provider.*
import sigil.provider.wire.OpenAIChatCompletions
import spice.http.HttpRequest
import spice.net.*

import scala.concurrent.duration.*

/**
 * DeepSeek provider — uses OpenAI-compatible chat-completions at
 * `https://api.deepseek.com/v1/chat/completions`. Strict-mode tool
 * schemas (grammar-constrained args) and `reasoning_effort` forwarding
 * are wired; `reasoning_content` deltas come back as `ThinkingDelta`s.
 *
 * for deterministic wire coverage that doesn't require balance.
 */
case class DeepSeekProvider(apiKey: String,
                            sigilRef: Sigil,
                            baseUrl: URL = url"https://api.deepseek.com",
                            /** Per-read idle timeout for the SSE stream. Fires
                              * only when no bytes arrive for the duration —
                              * slow-but-working streams keep going. */
                            tokenIdleTimeout: FiniteDuration = 120.seconds) extends Provider {
  override def `type`: ProviderType = ProviderType.DeepSeek
  override val providerKey: String = DeepSeek.Provider
  override protected def sigil: Sigil = sigilRef
  override def schemaDialect: SchemaDialect = wireConfig.schemaDialect

  private[deepseek] val wireConfig: OpenAIChatCompletions.Config = OpenAIChatCompletions.Config(
    providerNamespace = DeepSeek.Provider,
    providerName = "DeepSeek",
    schemaDialect = SchemaDialect.OpenAIStrict,
    reasoningPolicy = OpenAIChatCompletions.ReasoningPolicy.ReasoningEffortField,
    // #360 — reasoning models pause between the reasoning and answer
    // phases; extend the idle timeout for reasoning requests so a
    // slow-but-alive planner isn't cut at the 120s base.
    reasoningIdleTimeout = Some(6.minutes),
    multimodalPolicy = OpenAIChatCompletions.MultimodalPolicy.TextOnlyWithWarning,
    cacheKeys = CacheKeys.DeepSeek
  )

  private val bearerAuth: HttpRequest => HttpRequest =
    _.withHeader("Authorization", s"Bearer $apiKey")

  override def call(input: ProviderCall): Stream[ProviderEvent] =
    OpenAIChatCompletions.streamCall(input, sigilRef, baseUrl, bearerAuth, tokenIdleTimeout, wireConfig)

  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
    OpenAIChatCompletions.buildHttpRequest(input, sigilRef, baseUrl, bearerAuth, wireConfig)
}

object DeepSeekProvider {
  def create(sigil: Sigil, apiKey: String, baseUrl: URL = url"https://api.deepseek.com"): Task[DeepSeekProvider] =
    Task.pure(DeepSeekProvider(apiKey, sigil, baseUrl))
}
