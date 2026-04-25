package sigil.provider

import spice.net.URL

/**
 * A single content block within a multimodal [[ProviderMessage.User]].
 * Separate trait so the messages list can interleave text and images
 * the way every major provider's chat API expects for multimodal input.
 *
 *   - [[MessageContent.Text]]: plain text. The workhorse — most user
 *     messages contain exactly one of these.
 *   - [[MessageContent.Image]]: an image the LLM should see, referenced
 *     by URL (either a public URL or a `data:` URL carrying base64
 *     bytes). Apps that receive uploaded images are responsible for
 *     hosting them (or producing a data URL) before handing them to
 *     the provider.
 *
 * Providers that don't support multimodal input silently drop image
 * blocks (rather than failing). Vision-capable models render both.
 */
sealed trait MessageContent

object MessageContent {
  case class Text(text: String) extends MessageContent
  case class Image(url: URL, altText: Option[String] = None) extends MessageContent
}
