package sigil.provider.digitalocean

import rapid.Task
import sigil.Sigil
import sigil.provider.ProviderType
import sigil.provider.openai.OpenAIProvider
import spice.net.*

/**
 * Factory for an OpenAI-Responses-compatible provider pointed at
 * DigitalOcean Inference. DO's `/v1/responses` is wire-identical to
 * OpenAI's surface — same SSE grammar, same request shape, same
 * `previous_response_id` chain — so the framework reuses
 * [[OpenAIProvider]] with namespacing knobs flipped.
 *
 * The chat-completions sibling [[DigitalOceanProvider]] remains the
 * default. Use this opt-in factory when the app wants Responses-API
 * features (response-id chaining, `reasoning.summary`, web/file/code
 * built-in tools where DO surfaces them) against DO-hosted models.
 */
object DigitalOceanResponsesProvider {
  def create(sigil: Sigil,
             apiKey: String,
             baseUrl: URL = url"https://inference.do-ai.run"): Task[OpenAIProvider] =
    Task.pure(OpenAIProvider(
      apiKey = apiKey,
      sigilRef = sigil,
      baseUrl = baseUrl,
      providerType = ProviderType.DigitalOcean,
      providerNamespace = DigitalOcean.Provider,
      // Historically DO's Responses surface rejected
      // `tool_choice: "required"` with HTTP 424 "unexpected EOF".
      // Recent (2026-05) probes against kimi-k2.5 + 6 tools show
      // `required` is accepted and honoured. Keeping the downgrade
      // as the conservative default since the failure was observed
      // on at least one DO-hosted model in the past; apps that need
      // strict-required override the field.
      requiredToolChoiceSupported = false
    ))
}
