package sigil.provider.digitalocean

import rapid.Task
import sigil.Sigil
import sigil.provider.anthropic.{AnthropicAuthMode, AnthropicProvider}
import spice.net.*

/**
 * Factory for the Anthropic `/v1/messages` surface hosted on
 * DigitalOcean Inference. DO mirrors Anthropic's request body
 * (`messages`, `tools`, `tool_choice`, …) exactly; only the auth
 * shape differs — DO uses `Authorization: Bearer $DO_ACCESS_KEY`
 * while Anthropic's direct API uses `x-api-key` + `anthropic-version`.
 *
 * Apps targeting DO-hosted Claude models (e.g. `anthropic-claude-opus-4`)
 * call this factory; the resulting provider is a normal
 * [[AnthropicProvider]] with `authMode = Bearer` and the DO base URL.
 *
 * The provider still reports `type = ProviderType.Anthropic` because
 * the wire shape, tokenizer choice, and tool-translation logic are
 * Anthropic's — cost tracking and registry namespacing follow the
 * model family, not the hosting vendor. Apps that need per-vendor
 * attribution annotate at the app layer.
 */
object DigitalOceanMessagesProvider {
  def create(sigil: Sigil,
             apiKey: String,
             baseUrl: URL = url"https://inference.do-ai.run"): Task[AnthropicProvider] =
    Task.pure(AnthropicProvider(
      apiKey = apiKey,
      sigilRef = sigil,
      baseUrl = baseUrl,
      authMode = AnthropicAuthMode.Bearer
    ))
}
