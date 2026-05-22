package sigil.provider.cloudflare

/**
 * Cloudflare Workers AI — OpenAI-compatible chat-completions hosted at
 * `https://api.cloudflare.com/client/v4/accounts/{accountId}/ai/v1/chat/completions`.
 *
 * Model id format on the wire is `@cf/<vendor>/<model>` (e.g.
 * `@cf/moonshotai/kimi-k2.6`). The framework's `Id[Model]` namespaces
 * it as `cloudflare/@cf/moonshotai/kimi-k2.6`; [[stripProviderPrefix]]
 * removes the `cloudflare/` prefix before the model name reaches the
 * wire body's `model` field.
 */
object Cloudflare {
  val Provider: String = "cloudflare"

  /** Strip the `cloudflare/` namespace prefix from a Sigil model id,
    * leaving the raw `@cf/...` model name Cloudflare's OpenAI-compatible
    * endpoint expects in the request body. */
  def stripProviderPrefix(sigilModelId: String): String = {
    val prefix = s"$Provider/"
    if (sigilModelId.startsWith(prefix)) sigilModelId.drop(prefix.length) else sigilModelId
  }
}
