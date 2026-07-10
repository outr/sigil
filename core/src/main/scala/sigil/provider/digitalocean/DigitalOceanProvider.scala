package sigil.provider.digitalocean

import rapid.{Stream, Task}
import sigil.Sigil
import sigil.provider.*
import sigil.provider.wire.OpenAIChatCompletions
import spice.http.HttpRequest
import spice.net.*

import scala.concurrent.duration.*

/**
 * DigitalOcean Inference provider — OpenAI-compatible chat-completions
 * at `https://inference.do-ai.run/v1/chat/completions`. Hosted models
 * (kimi-k2.5, llama, mistral, …) all speak the same wire shape.
 *
 * Strict-mode tool schemas (`strict: true`) are on (`strictModeCapable
 * = true` in the wire config); schema shaping goes through
 * [[StrictSchema.forOpenAIStrict]] (closed-object form,
 * `additionalProperties: false`, `pattern`/`format`/numeric bounds
 * stripped). Tools whose `Input` contains a `DefType.Json` field opt
 * out per-tool — strict mode and any-JSON aren't compatible.
 * `reasoning_effort` is not used here; Kimi reasoning is steered via
 * the `/think` / `/no_think` system-prompt directive instead (see
 * [[applyKimiReasoningDirective]] and sigil bug #155). DO
 * chat-completions supports vision via OpenAI's content-array shape
 * for VLMs (kimi K2.5/K2.6, Nemotron Nano 12B v2 VL).
 *
 * For kimi-* hosted models, [[ReasoningMode]] is translated into the
 * `/think` / `/no_think` system-prompt directive (sigil bug #155).
 * Non-kimi models ignore the directive (it's just text).
 */
case class DigitalOceanProvider(apiKey: String,
                                sigilRef: Sigil,
                                baseUrl: URL = url"https://inference.do-ai.run",
                                /** Per-read idle timeout for the SSE stream. Fires
                                  * only when no bytes arrive for the duration —
                                  * slow-but-working streams keep going. */
                                tokenIdleTimeout: FiniteDuration = 120.seconds) extends Provider {
  override def `type`: ProviderType = ProviderType.DigitalOcean
  override val providerKey: String = DigitalOcean.Provider
  override protected def sigil: Sigil = sigilRef

  private val wireConfig: OpenAIChatCompletions.Config = OpenAIChatCompletions.Config(
    providerNamespace = DigitalOcean.Provider,
    providerName = "DigitalOcean",
    strictModeCapable = true,
    multimodalPolicy = OpenAIChatCompletions.MultimodalPolicy.OpenAIArrayForm,
    // Sigil bug #161 — DO's kimi-k2.5 deployment intermittently emits
    // either degenerate `" The!!!!"` reasoning_content or null-padded
    // content tokens until `max_tokens` cap, with no usable content or
    // tool calls. The framework cannot recover from this in-conversation,
    // so we raise [[ProviderStreamException]] at the wire boundary and
    // let [[ProviderStrategy.errorClassifier]] (default classifier maps
    // this to `Fallthrough`) route to the next candidate.
    emptyBudgetBurnThrows = true,
    preprocess = { call =>
      val modelName = DigitalOcean.stripProviderPrefix(call.modelId.value)
      val systemContent = applyKimiReasoningDirective(call.system, modelName, call.generationSettings.reasoningMode)
      OpenAIChatCompletions.Preprocessed(systemContent, call.messagesWithVolatileTail)
    }
  )

  private val bearerAuth: HttpRequest => HttpRequest =
    _.withHeader("Authorization", s"Bearer $apiKey")

  override def call(input: ProviderCall): Stream[ProviderEvent] =
    OpenAIChatCompletions.streamCall(input, sigilRef, baseUrl, bearerAuth, tokenIdleTimeout, wireConfig)

  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
    OpenAIChatCompletions.buildHttpRequest(input, sigilRef, baseUrl, bearerAuth, wireConfig)

  /** Inject kimi's `/think` / `/no_think` system-prompt directive
    * when [[ReasoningMode]] forces a non-default mode. kimi-k2.5
    * defaults to thinking-on for non-trivial system prompts; kimi-
    * k2.6 is thinking-by-default unconditionally. Apps wanting the
    * fast non-thinking path on either model set
    * `GenerationSettings(reasoningMode = ReasoningMode.Off)` and
    * the provider stamps `/no_think` here. Sigil bug #155. */
  private def applyKimiReasoningDirective(systemPrompt: String,
                                          modelName: String,
                                          mode: ReasoningMode): String = {
    val isKimi = modelName.toLowerCase.startsWith("kimi-")
    val directive: Option[String] = if (!isKimi) None else mode match {
      case ReasoningMode.Off  => Some("/no_think")
      case ReasoningMode.On   => Some("/think")
      case ReasoningMode.Auto => None
    }
    directive match {
      case None      => systemPrompt
      case Some(dir) =>
        if (systemPrompt.isEmpty) dir
        else s"$systemPrompt\n\n$dir"
    }
  }
}

object DigitalOceanProvider {
  def create(sigil: Sigil, apiKey: String, baseUrl: URL = url"https://inference.do-ai.run"): Task[DigitalOceanProvider] =
    Task.pure(DigitalOceanProvider(apiKey, sigil, baseUrl))
}
