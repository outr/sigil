package sigil.provider.openrouter

/**
 * OpenRouter-specific constants. OpenRouter is a unified gateway at
 * `https://openrouter.ai/api/v1/chat/completions` that proxies calls
 * to many upstream model families under one OpenAI-compatible
 * chat-completions wire. Model ids follow `<vendor>/<slug>` (e.g.
 * `openai/gpt-5.2`, `anthropic/claude-opus-4.7`); the gateway
 * accepts those verbatim as the `model` field.
 *
 * The framework's existing [[sigil.controller.OpenRouter]] loader
 * already populates [[sigil.cache.ModelRegistry]] from OpenRouter's
 * `/api/v1/models` catalog using those raw `<vendor>/<slug>` ids, so
 * no additional namespacing is required for ids that originate from
 * the OpenRouter catalog. Apps that want to route a model id verbatim
 * through OpenRouter pass `Model.id("openai", "gpt-5.2")` etc.
 *
 * OpenRouter's vocabulary is the OpenAI-canonical superset:
 *
 *   - `tool_choice` accepts `"auto"`, `"none"`, `"required"`, and
 *     the function-form `{type: "function", function: {name: …}}`.
 *   - Per-function `strict: true` is honored (grammar-constrained
 *     decoding when the upstream supports it; the gateway shims it
 *     for upstreams that don't).
 *   - `response_format: {type: "json_schema", …}` is honored.
 *   - Reasoning models advertise `reasoning_effort` natively.
 *   - Multimodal models accept the OpenAI content-array shape.
 *
 * Defaults of [[sigil.provider.wire.OpenAIChatCompletions.Config]]
 * therefore mostly apply — strict-mode capable, honors strict,
 * `tool_choice` shape for forced calls, OpenAI-array multimodal,
 * `reasoning_effort` field for reasoning forwarding.
 */
object OpenRouter {
  val Provider: String = "openrouter"

  /**
   * Optional `HTTP-Referer` header value used for OpenRouter's
   * public-leaderboard attribution. Apps that want their traffic
   * credited set this to their site URL. Defaults to none.
   */
  type Referer = String

  /**
   * Optional `X-Title` header — friendly app name for the same
   * leaderboard. Defaults to none.
   */
  type Title = String

  /**
   * OpenRouter provider slugs whose endpoints are hosted in (or
   * affiliated with) mainland China. Sourced from
   * `https://openrouter.ai/api/v1/providers` — each entry self-
   * declares headquarters / datacenter locations.
   *
   * Two tiers folded into one set so callers get a conservative
   * default — apps that want a stricter "datacenters-in-CN-only"
   * filter narrow the set, apps that want to allow SG-headquartered
   * Chinese-affiliated providers widen it:
   *
   *   - Direct mainland-China hosting (HQ or datacenter in CN):
   *     `deepseek`, `alibaba`, `xiaomi`, `baidu`, `streamlake`,
   *     `nex-agi`.
   *   - Chinese-affiliated companies headquartered in Singapore
   *     (no declared CN datacenters today, but parent companies are
   *     PRC-domiciled and could route through CN in the future
   *     without OpenRouter changing the slug):
   *     `siliconflow`, `minimax`, `z-ai`, `moonshotai`.
   *
   * Verify against the live `/providers` endpoint when widening or
   * narrowing this list — OpenRouter rotates upstreams. The
   * [[OpenRouterProvider.providerRouting]] override is the right
   * surface for app-specific deviations from this default.
   */
  val ChineseHostedSlugs: Set[String] = Set(
    "deepseek",
    "alibaba",
    "xiaomi",
    "baidu",
    "streamlake",
    "nex-agi",
    "siliconflow",
    "minimax",
    "z-ai",
    "moonshotai"
  )
}
