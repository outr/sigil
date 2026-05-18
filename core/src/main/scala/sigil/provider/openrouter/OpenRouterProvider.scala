package sigil.provider.openrouter

import fabric.*
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.db.Model
import sigil.provider.*
import sigil.provider.wire.OpenAIChatCompletions
import spice.http.HttpRequest
import spice.net.*

import scala.concurrent.duration.*

/**
 * OpenRouter provider — OpenAI-compatible chat-completions at
 * `https://openrouter.ai/api/v1/chat/completions`. OpenRouter is a
 * unified gateway that proxies requests to many upstream model
 * families under one wire format. Apps wire it once and route any
 * `<vendor>/<slug>` model id through it (`openai/gpt-5.2`,
 * `anthropic/claude-opus-4.7`, `meta-llama/llama-4-70b`, etc.).
 *
 * Wire posture is the canonical OpenAI chat-completions superset:
 *
 *   - `tool_choice: "required"` and the function-form
 *     `{type: "function", function: {name}}` are honored — keep the
 *     default `ForcedCallShape.ToolChoice`. No `response_format`
 *     substitution needed (contrast with [[sigil.provider.deepinfra.DeepInfraProvider]]
 *     which has to substitute because DeepInfra's `tool_choice`
 *     vocabulary is `{"auto", "none"}` only).
 *   - Per-function `strict: true` is honored — keep
 *     `strictModeCapable = true` AND `honorsStrict = true`. The
 *     gateway shims strict decoding for upstreams that don't
 *     natively grammar-constrain.
 *   - Multimodal content rides the OpenAI array form for VLMs.
 *   - `reasoning_effort` is the canonical reasoning forwarding
 *     field. Apps that target reasoning models pass
 *     `GenerationSettings(reasoningMode = ReasoningMode.On, effort
 *     = Some(Effort.Medium))` and the shared wire emits
 *     `reasoning_effort: "medium"`.
 *
 * Model ids are sent VERBATIM as the wire `model` field. OpenRouter's
 * catalog (loaded into [[sigil.cache.ModelRegistry]] by
 * [[sigil.controller.OpenRouter.refreshModels]]) stores models under
 * their raw `<vendor>/<slug>` ids, so no per-provider namespace
 * stripping is required. The wire's `stripNamespace` no-ops for ids
 * that don't carry the `openrouter/` prefix — apps can optionally use
 * `Model.id("openrouter", "openai/gpt-5.2")` to disambiguate against
 * a parallel direct-vendor provider, and the prefix strips off
 * before the call.
 *
 * Optional headers for OpenRouter's public-leaderboard attribution:
 * pass `httpReferer` / `xTitle` constructor args to identify your
 * application on the rankings. Both default to `None` (no
 * attribution sent).
 *
 * == Geographic-routing restriction ==
 *
 * Every outbound request includes a top-level `provider` block
 * (see [[OpenRouterProviderRouting]]) populated by default with
 * [[OpenRouterProviderRouting.noChineseHosting]] — an `ignore`
 * deny-list of every OpenRouter provider slug whose endpoints are
 * hosted in (or affiliated with) mainland China. The deny-list is
 * curated in [[OpenRouter.ChineseHostedSlugs]]; verify against the
 * live `/api/v1/providers` catalog when widening or narrowing it.
 *
 * Apps with different geographic / data-residency policy override
 * `providerRouting` at construction:
 *
 * {{{
 *   // Permit Chinese-hosted upstreams (NOT recommended for most apps):
 *   OpenRouterProvider(apiKey = …, sigilRef = sigil,
 *                      providerRouting = OpenRouterProviderRouting())
 *
 *   // Add EU-only data-collection-deny + zdr on top of the
 *   // no-China-hosting baseline:
 *   OpenRouterProvider(apiKey = …, sigilRef = sigil,
 *                      providerRouting = OpenRouterProviderRouting.noChineseHosting.copy(
 *                        dataCollection = Some("deny"),
 *                        zdr = Some(true)
 *                      ))
 * }}}
 *
 * Pass `OpenRouterProviderRouting()` (all knobs `None`) only when
 * you have audited the implications.
 */
case class OpenRouterProvider(apiKey: String,
                              sigilRef: Sigil,
                              baseUrl: URL = url"https://openrouter.ai",
                              /**
                               * Per-read idle timeout for the SSE stream. Fires
                               * only when no bytes arrive for the duration —
                               * slow-but-working streams keep going.
                               */
                              tokenIdleTimeout: FiniteDuration = 120.seconds,
                              /**
                               * Optional `HTTP-Referer` header — apps' site URL
                               * for OpenRouter's public-leaderboard attribution.
                               * `None` (default) sends no Referer.
                               */
                              httpReferer: Option[String] = None,
                              /**
                               * Optional `X-Title` header — friendly app name
                               * for the same leaderboard. `None` (default) sends
                               * no Title.
                               */
                              xTitle: Option[String] = None,
                              /**
                               * OpenRouter `provider` routing block emitted on
                               * every request. Defaults to
                               * [[OpenRouterProviderRouting.noChineseHosting]] —
                               * deny-list of mainland-China-hosted upstreams.
                               * Pass a different value for app-specific policy;
                               * pass `OpenRouterProviderRouting()` to omit the
                               * block entirely (NOT recommended).
                               */
                              providerRouting: OpenRouterProviderRouting =
                                OpenRouterProviderRouting.noChineseHosting)
  extends Provider {
  override def `type`: ProviderType = ProviderType.OpenRouter
  override val providerKey: String = OpenRouter.Provider
  override protected def sigil: Sigil = sigilRef

  /**
   * OpenRouter is a meta-gateway — its catalog (populated by
   * [[sigil.controller.OpenRouter.refreshModels]]) stores models
   * under their original `<vendor>/<slug>` ids (e.g. `openai/gpt-
   * 5.2`), NOT under `openrouter/<vendor>/<slug>`. The default
   * `Provider.models` filters by `providerKey` and would return
   * nothing useful here. Override to return the full registry,
   * matching OpenRouter's "any model in the catalog is routable
   * through me" semantics. Apps with multiple providers should
   * scope their [[Sigil.providerFor]] dispatch explicitly rather
   * than relying on `provider.models` to disambiguate.
   */
  override def models: List[Model] = sigilRef.cache.all

  private val authAndAttribution: HttpRequest => HttpRequest = { req =>
    val withAuth = req.withHeader("Authorization", s"Bearer $apiKey")
    val withReferer = httpReferer.fold(withAuth)(r => withAuth.withHeader("HTTP-Referer", r))
    xTitle.fold(withReferer)(t => withReferer.withHeader("X-Title", t))
  }

  private val wireConfig: OpenAIChatCompletions.Config = OpenAIChatCompletions.Config(
    providerNamespace = OpenRouter.Provider,
    providerName = "OpenRouter",
    // OpenRouter's chat-completions endpoint sits under `/api/v1/...`
    // (not the bare `/v1/...` the shared wire defaults to).
    path = "/api/v1/chat/completions",
    // OpenRouter accepts and (via gateway shimming) enforces
    // per-function `strict: true` — keep both Sigil-side reshaping
    // and the wire flag on.
    strictModeCapable = true,
    honorsStrict = true,
    // OpenRouter honors `tool_choice: "required"` and the
    // function-form directly — no response_format substitution.
    forcedCallShape = OpenAIChatCompletions.ForcedCallShape.ToolChoice,
    // `reasoning_effort` is the canonical forwarding field for
    // reasoning-capable models on OpenRouter (OpenAI, DeepSeek,
    // Qwen3-Thinking, …). The shared wire policy maps Sigil's
    // ReasoningMode (Auto/On/Off) + optional Effort to the right
    // enum value.
    reasoningPolicy = OpenAIChatCompletions.ReasoningPolicy.ReasoningEffortField,
    // Multimodal models on OpenRouter accept the OpenAI content-
    // array shape. Text-only models on the gateway tolerate it as
    // a long-form text input.
    multimodalPolicy = OpenAIChatCompletions.MultimodalPolicy.OpenAIArrayForm,
    // Inject the `provider` routing block on every outbound
    // request. The default (`OpenRouterProviderRouting.noChineseHosting`)
    // populates `ignore` with the curated set of mainland-China-
    // hosted slugs so traffic NEVER routes to those endpoints.
    // Empty routing (no constraints) suppresses the field entirely.
    extraBody = _ => {
      val routingJson = providerRouting.toJson
      val isEmpty = routingJson match {
        case o: Obj => o.value.isEmpty
        case _ => true
      }
      if (isEmpty) Vector.empty else Vector("provider" -> routingJson)
    }
  )

  override def call(input: ProviderCall): Stream[ProviderEvent] =
    OpenAIChatCompletions.streamCall(input, sigilRef, baseUrl, authAndAttribution, tokenIdleTimeout, wireConfig)

  override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
    OpenAIChatCompletions.buildHttpRequest(input, sigilRef, baseUrl, authAndAttribution, wireConfig)
}

object OpenRouterProvider {
  def create(sigil: Sigil,
             apiKey: String,
             baseUrl: URL = url"https://openrouter.ai",
             httpReferer: Option[String] = None,
             xTitle: Option[String] = None,
             providerRouting: OpenRouterProviderRouting =
               OpenRouterProviderRouting.noChineseHosting): Task[OpenRouterProvider] =
    Task.pure(OpenRouterProvider(
      apiKey = apiKey,
      sigilRef = sigil,
      baseUrl = baseUrl,
      httpReferer = httpReferer,
      xTitle = xTitle,
      providerRouting = providerRouting
    ))
}
