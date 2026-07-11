package sigil.provider

import fabric.rw.*

/**
 * A generated image as delivered by a provider's image-generation
 * stream.
 *
 *   - [[Hosted]] — a remote URL the provider returned; small, safe to
 *     reference inline.
 *   - [[Inline]] — base64-encoded image bytes the provider returned
 *     in the response body. The orchestrator persists these via
 *     `Sigil.storeBytes` and references the stored file by URL, so a
 *     multi-megabyte payload never lands in conversation history.
 */
enum ProviderImage derives RW {
  case Hosted(url: spice.net.URL)
  case Inline(base64: String, contentType: String)

  /**
   * Byte-safe debug rendering — never echoes the base64 payload.
   */
  def describe: String = this match {
    case Hosted(url) => s"Hosted($url)"
    case Inline(b64, ct) => s"Inline($ct, ${b64.length} b64 chars)"
  }
}
