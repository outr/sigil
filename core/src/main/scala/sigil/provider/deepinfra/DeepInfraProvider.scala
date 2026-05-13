package sigil.provider.deepinfra

import rapid.{Stream, Task}
import sigil.Sigil
import sigil.provider.*
import sigil.provider.wire.OpenAIChatCompletions
import spice.http.HttpRequest
import spice.net.*

import scala.concurrent.duration.*

/**
 * DeepInfra provider — OpenAI-compatible chat-completions at
 * `https://api.deepinfra.com/v1/openai/chat/completions`. Hosts the
 * Kimi family (`moonshotai/Kimi-K2.5`, `moonshotai/Kimi-K2.6`),
 * Llama, Qwen, and other open-weight models on vLLM / SGLang
 * upstream. Function calling and streaming SSE both supported.
 *
 * **Why this provider exists alongside [[sigil.provider.digitalocean.DigitalOceanProvider]]:**
 * sigil bug #161 documents that DO's kimi-k2.5 / kimi-k2.6
 * deployment catastrophically degenerates on non-trivial agent
 * prompts (`" The!!!!"` reasoning loops or null-padded content),
 * burning the entire `max_tokens` budget per failed call with no
 * recovery. DeepInfra is the documented alternative — cheaper on
 * k2.6 (~20% on input, ~12% on output), $0.07/1M cached input
 * (vs DO's no-cache-tier), and runs the upstream-supported
 * inference stack so the deployment shouldn't exhibit DO's
 * degeneration. Apps wiring kimi candidates into a
 * [[ProviderStrategy]] route them here.
 *
 * Schemas pass through [[StrictSchema.stripUnsupportedKeys]] —
 * conservative dialect-friendly shape; no `strict: true` since
 * DeepInfra documents OpenAI compatibility, not strict-mode.
 */
case class DeepInfraProvider(apiKey: String,
                             sigilRef: Sigil,
                             baseUrl: URL = url"https://api.deepinfra.com",
                             /** Per-read idle timeout for the SSE stream. Fires
                               * only when no bytes arrive for the duration —
                               * slow-but-working streams keep going. */
                             tokenIdleTimeout: FiniteDuration = 120.seconds) extends Provider {
  override def `type`: ProviderType = ProviderType.DeepInfra
  override val providerKey: String = DeepInfra.Provider
  override protected def sigil: Sigil = sigilRef

  private val wireConfig: OpenAIChatCompletions.Config = OpenAIChatCompletions.Config(
    providerNamespace = DeepInfra.Provider,
    providerName      = "DeepInfra",
    path              = "/v1/openai/chat/completions",
    // DeepInfra exposes the canonical OpenAI `reasoning_effort` field
    // on /v1/openai/chat/completions and honors `none | low | medium |
    // high`. Verified against kimi-k2.5: `none` zeroes
    // `reasoning_content` and converges on a direct tool call (16
    // compl tokens), while `low/medium/high` produce graduated
    // reasoning. The shared wire policy translates
    // GenerationSettings.reasoningMode (Auto/On/Off) + optional
    // Effort into the right `reasoning_effort` value.
    reasoningPolicy   = OpenAIChatCompletions.ReasoningPolicy.ReasoningEffortField,
    multimodalPolicy  = OpenAIChatCompletions.MultimodalPolicy.OpenAIArrayForm
  )

  private val bearerAuth: HttpRequest => HttpRequest =
    _.withHeader("Authorization", s"Bearer $apiKey")

  override def call(input: ProviderCall): Stream[ProviderEvent] =
    OpenAIChatCompletions.streamCall(input, sigilRef, baseUrl, bearerAuth, tokenIdleTimeout, wireConfig)

  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
    OpenAIChatCompletions.buildHttpRequest(input, sigilRef, baseUrl, bearerAuth, wireConfig)
}

object DeepInfraProvider {
  def create(sigil: Sigil, apiKey: String, baseUrl: URL = url"https://api.deepinfra.com"): Task[DeepInfraProvider] =
    Task.pure(DeepInfraProvider(apiKey, sigil, baseUrl))
}
