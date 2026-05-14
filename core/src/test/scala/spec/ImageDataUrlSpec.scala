package spec

import fabric.*
import fabric.io.JsonFormatter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.google.GoogleProvider
import sigil.provider.{MessageContent, ProviderMessage}
import spice.net.*

/**
 * Sigil audit H1 + H2 — inline-image-bytes handling on every multimodal
 * provider. Pre-fix [[MessageContent.Image]] carried a spice `URL`, but
 * spice's URL parser only handles HTTP-shaped URLs (`data:image/...`
 * mis-parses to `https://data:image/...`). Apps with raw image bytes had
 * no working path. The fix adds [[MessageContent.ImageBytes]] — explicit
 * `(mediaType, base64)` — and every provider renders it into its native
 * inline shape:
 *
 *   - OpenAI Responses: `input_image` + `data:` URL (built locally).
 *   - OpenAI chat-completions: `image_url` + `data:` URL.
 *   - Anthropic: `source{type: base64, media_type, data}`.
 *   - Gemini: `inlineData{mimeType, data}`.
 */
class ImageDataUrlSpec extends AnyWordSpec with Matchers {

  "GoogleProvider.renderImageUrl" should {
    "emit fileData with MIME sniffed from PNG extension" in {
      val u = url"https://example.com/cat.png"
      val rendered = GoogleProvider.renderImageUrl(u)
      val fd = rendered.asObj.value("fileData").asObj
      fd.value("fileUri").asStr.value shouldBe "https://example.com/cat.png"
      fd.value("mimeType").asStr.value shouldBe "image/png"
    }

    "default to image/jpeg when the URL has no recognizable extension" in {
      val u = url"https://example.com/image"
      val rendered = GoogleProvider.renderImageUrl(u)
      rendered.asObj.value("fileData").asObj.value("mimeType").asStr.value shouldBe "image/jpeg"
    }

    "sniff webp" in {
      val u = url"https://example.com/photo.webp"
      val rendered = GoogleProvider.renderImageUrl(u)
      rendered.asObj.value("fileData").asObj.value("mimeType").asStr.value shouldBe "image/webp"
    }
  }

  "MessageContent.ImageBytes — surface shape" should {
    "round-trip mediaType + base64 through the case-class constructor" in {
      val bytes = MessageContent.ImageBytes("image/jpeg", "/9j/4AAQSkZJRg==")
      bytes.mediaType shouldBe "image/jpeg"
      bytes.base64 shouldBe "/9j/4AAQSkZJRg=="
      bytes.altText shouldBe None
    }

    "accept altText" in {
      val bytes = MessageContent.ImageBytes("image/png", "iVBOR", altText = Some("a kitten"))
      bytes.altText shouldBe Some("a kitten")
    }

    "be usable inside ProviderMessage.User alongside Text blocks" in {
      val msg = ProviderMessage.User(Vector(
        MessageContent.Text("describe this:"),
        MessageContent.ImageBytes("image/jpeg", "/9j/4AAQ")
      ))
      msg.content should have size 2
    }
  }
}
