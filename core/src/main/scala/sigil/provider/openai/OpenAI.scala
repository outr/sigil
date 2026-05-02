package sigil.provider.openai

/**
 * OpenAI-specific constants. Model metadata (context, pricing,
 * tokenizer, etc.) lives in [[sigil.cache.ModelRegistry]] — populated by
 * [[sigil.controller.OpenRouter.refreshModels]] — and is read fresh on
 * each access via `Provider.models`.
 */
object OpenAI {
  val Provider: String = "openai"

  /** Strip the `openai/` provider prefix from a sigil model id so the
    * wire request carries the bare OpenAI id. */
  def stripProviderPrefix(sigilModelId: String): String = {
    val prefix = s"$Provider/"
    if (sigilModelId.startsWith(prefix)) sigilModelId.drop(prefix.length) else sigilModelId
  }

  /**
   * Cold-cache safety net for [[OpenAIProvider]]'s sampling-param
   * filter. Models whose name starts with one of these prefixes
   * have fixed sampling (temperature locked to 1.0, no `top_p`)
   * and reject requests carrying those fields.
   *
   * The provider's primary check goes through
   * [[sigil.Sigil.supportsParameter]] (driven by
   * `Model.supportedParameters` from the registry); this list is
   * the fallback when the registry hasn't been populated yet
   * (first boot before `OpenRouter.refreshModels`, offline
   * sessions, etc.). Adding a new restricted family is one line
   * here — the call sites stay catalog-driven.
   */
  val fixedSamplingPrefixes: Set[String] = Set("gpt-5", "o1", "o3")

  /**
   * OpenAI model families that emit `reasoning` output items in the
   * Responses API stream and require the corresponding state to be
   * either replayed (encrypted_content round-trip) or recovered via
   * `previous_response_id` on subsequent requests. Bug #62.
   *
   * `OpenAIProvider` uses this to opt the request into
   * `reasoning.summary = "auto"` and
   * `include = ["reasoning.encrypted_content"]` so the response
   * carries content the framework can persist + replay. Other
   * models (gpt-4o, gpt-4.1, gpt-3.5) don't emit reasoning items
   * and some endpoints reject the include keyword as unknown,
   * so the opt-in is gated.
   *
   * Prefix-based detection mirrors [[fixedSamplingPrefixes]] — same
   * cold-cache rationale: a `Model.family` field would be cleaner
   * long-term but the registry isn't always populated when the
   * filter runs (first boot before `OpenRouter.refreshModels`,
   * offline sessions). `o4` is included pre-emptively per bug #62's
   * write-up; adding a new family is a one-line change here.
   */
  val reasoningModelPrefixes: Set[String] = Set("gpt-5", "o1", "o3", "o4")
}
