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
 * Schemas pass through [[StrictSchema.stripUnsupportedKeys]] —
 * conservative dialect-friendly shape; no `strict: true` since
 * DeepInfra documents OpenAI compatibility, not strict-mode.
 *
 * **`ReasoningMode.Off` is unsafe on kimi-k2.5 here.** Empirically
 * verified (40-sample curl battery, 2026-05-13): setting
 * `reasoning_effort: "none"` on kimi-k2.5 / kimi-k2.6 disables both
 * reasoning AND tool-call compliance. The model bypasses
 * `tool_choice: required`, emits plain natural-language content
 * instead of calling tools, and the `function.name` field on the
 * rare tool calls that DO fire gets filled with the `tool_call.id`
 * value instead of the actual tool name (~80% slot-confusion rate).
 * With reasoning enabled (Auto, or On + any Effort), compliance is
 * 100%. Apps wanting bounded reasoning latency on kimi should set
 * `GenerationSettings(reasoningMode = ReasoningMode.On, effort =
 * Some(Effort.Low))` instead of `Off` — caps reasoning at ~120
 * tokens (~1-2s) while preserving tool compliance.
 */
case class DeepInfraProvider(apiKey: String,
                             sigilRef: Sigil,
                             baseUrl: URL = url"https://api.deepinfra.com",
                             /**
                              * Per-read idle timeout for the SSE stream. Fires
                              * only when no bytes arrive for the duration —
                              * slow-but-working streams keep going.
                              */
                             tokenIdleTimeout: FiniteDuration = 120.seconds)
  extends Provider {
  override def `type`: ProviderType = ProviderType.DeepInfra
  override val providerKey: String = DeepInfra.Provider
  override protected def sigil: Sigil = sigilRef
  override def schemaDialect: SchemaDialect = wireConfig.schemaDialect

  private[deepinfra] val wireConfig: OpenAIChatCompletions.Config = OpenAIChatCompletions.Config(
    providerNamespace = DeepInfra.Provider,
    providerName = "DeepInfra",
    path = "/v1/openai/chat/completions",
    // DeepInfra accepts `strict: true` (HTTP 200) but doesn't enforce
    // it (verified against Kimi-K2.5 wire logs where the model emitted
    // JSON arrays despite per-tool strict). The strict dialect still
    // shapes the schema (closed-object form for the validator), but we
    // don't pretend the backend honors the flag — strip it from the
    // wire body via honorsStrict.
    schemaDialect = SchemaDialect.OpenAIStrict,
    honorsStrict = false,
    forcedCallShape = OpenAIChatCompletions.ForcedCallShape.ResponseFormatJsonSchema,
    // DeepInfra exposes the canonical OpenAI `reasoning_effort` field
    // on /v1/openai/chat/completions and honors `none | low | medium |
    // high`. Verified against kimi-k2.5: `none` zeroes
    // `reasoning_content` and converges on a direct tool call (16
    // compl tokens), while `low/medium/high` produce graduated
    // reasoning. The shared wire policy translates
    // GenerationSettings.reasoningMode (Auto/On/Off) + optional
    // Effort into the right `reasoning_effort` value.
    reasoningPolicy = OpenAIChatCompletions.ReasoningPolicy.ReasoningEffortField,
    reasoningIdleTimeout = Some(6.minutes),
    multimodalPolicy = OpenAIChatCompletions.MultimodalPolicy.OpenAIArrayForm
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
